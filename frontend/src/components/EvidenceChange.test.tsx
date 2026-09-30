import { render, screen, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'

import type { PlanResult } from '../session/useDashboard'

import { adjustmentHistoryContent } from '../content/adjustmentHistory'
import { calculationEvidenceContent } from '../content/calculationEvidence'
import { planResultContent } from '../content/planResult'
import { planVerificationContent } from '../content/planVerification'
import { formatAmount, formatSeoulTime } from '../format/decimal'
import { parseEvidence, type CalculationEvidence } from '../session/useCalculationEvidence'
import { isPlanResult } from '../session/useDashboard'
import {
  BASIS_AT,
  calculationEvidenceFixture,
  EVIDENCE_IDS,
  evidenceChangeFixture,
  evidenceResultFixture,
} from '../test/planResultFixture'
import { getObservedStorageEntries } from '../test/setup'
import { EvidenceChange } from './EvidenceChange'

const copy = planVerificationContent.evidence
const ABSENT = planVerificationContent.absent
const PREVIOUS_BASIS = '2026-09-26T01:00:00Z'
const LATE_AT = '2026-09-25T00:00:01Z'
/** The third calculation of the three-version history. */
const THIRD = '1c2d3e4f-5a6b-4c7d-8e9f-0a1b2c3d4e5f'

/** The shown v2 bundle: a precision verification applied one late deposit of 25 USDT. */
function v2(): Record<string, unknown> {
  return evidenceResultFixture({
    pointerVersion: 2,
    calculationId: EVIDENCE_IDS.newerCalculation,
    flowCount: 1,
  })
}

function shown(body: Record<string, unknown> = v2()): PlanResult {
  expect(isPlanResult(body)).toBe(true)
  return body as unknown as PlanResult
}

/** The six metrics of v1 beside the shown v2 metrics (the new side is always the shown one). */
function comparedMetrics(result: Record<string, unknown>): Record<string, unknown>[] {
  const previous = ['1225', '1185', '40', '1303.5', '0.07', '0.6']
  return (result.metrics as Record<string, unknown>[]).map((metric, index) => ({
    metric: metric.metric,
    previousValue: previous[index],
    previousUnavailableReason: null,
    value: metric.value ?? null,
    unavailableReason: metric.unavailableReason ?? null,
  }))
}

function lateEvent(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    sourceId: 'late-event-01',
    occurredAt: LATE_AT,
    changeKind: 'ADDED',
    late: true,
    transactionType: 'TRANSFER_IN',
    previousUsdValue: null,
    previousEstimated: null,
    usdValue: '25',
    estimated: false,
    evidenceType: 'DIRECT_NORMALIZATION',
    // Discovered by the verification before it published v2 (never after it was applied).
    discoveredAt: '2026-09-27T00:59:30.123Z',
    appliedAt: '2026-09-27T01:00:01Z',
    ...overrides,
  }
}

/** The evidence of v2: v1 and v2 in the official list, and the change from v1. */
function v2Evidence(change: Record<string, unknown> = {}): Record<string, unknown> {
  const result = v2()
  return calculationEvidenceFixture(result, {
    versions: {
      official: [
        {
          pointerVersion: 1,
          calculationId: EVIDENCE_IDS.calculation,
          publishedAt: '2026-09-26T01:00:01Z',
          appliedUntil: result.publishedAt,
          current: false,
        },
        {
          pointerVersion: 2,
          calculationId: EVIDENCE_IDS.newerCalculation,
          publishedAt: result.publishedAt,
          appliedUntil: null,
          current: true,
        },
      ],
      unpublished: [],
    },
    change: evidenceChangeFixture({
      kind: 'CORRECTED',
      purpose: 'VERIFY',
      reason: 'GAP_SUSPECTED',
      targets: [
        { kind: 'ANOMALY', start: '2026-09-25T00:00:00Z', end: '2026-09-26T00:00:01Z' },
        { kind: 'NEW', start: PREVIOUS_BASIS, end: result.basisAt },
      ],
      reused: [{ kind: 'REUSED', start: '2026-09-26T00:00:01Z', end: PREVIOUS_BASIS }],
      previous: {
        pointerVersion: 1,
        calculationId: EVIDENCE_IDS.calculation,
        basisAt: PREVIOUS_BASIS,
      },
      firstAffectedAt: LATE_AT,
      metrics: comparedMetrics(result),
      events: [lateEvent()],
      ...change,
    }),
  })
}

function evidence(body: Record<string, unknown> = v2Evidence()): CalculationEvidence {
  const parsed = parseEvidence(body, shown())
  if (parsed === null) throw new Error('the fixture is not whole')
  return parsed
}

function part(): HTMLElement {
  return screen.getByRole('group', { name: copy.title })
}

/** The change part of the shown v2, compared against its own basis time. */
function renderChange(body: CalculationEvidence = evidence()): void {
  render(<EvidenceChange basisAt={v2().basisAt as string} evidence={body} />)
}

/** The list item of the changed flow with this Bybit id. */
function eventItem(sourceId: string): HTMLElement {
  const item = within(part())
    .getAllByRole('listitem')
    .find((entry) => entry.textContent.includes(sourceId))
  if (item === undefined) throw new Error('no event item')
  return item
}

describe('EvidenceChange', () => {
  it('names the kind, the job and reason, the ranges read again and reused, the previous version and its basis, and the impact start', () => {
    renderChange()
    const change = part()
    expect(within(change).getByRole('heading', { level: 5 })).toHaveTextContent(copy.title)
    expect(change).toHaveTextContent(copy.kinds.CORRECTED ?? 'missing')
    expect(change).toHaveTextContent(planVerificationContent.purposes.VERIFY ?? 'missing')
    expect(change).toHaveTextContent(planVerificationContent.jobReasons.GAP_SUSPECTED ?? 'missing')
    expect(change).toHaveTextContent(planVerificationContent.rangeKinds.ANOMALY ?? 'missing')
    expect(change).toHaveTextContent(planVerificationContent.rangeKinds.REUSED ?? 'missing')
    expect(change).toHaveTextContent(copy.previous)
    expect(change).toHaveTextContent(EVIDENCE_IDS.calculation)
    // The impact start and the event time are the same instant, as machine values.
    // eslint-disable-next-line testing-library/no-node-access -- 시각의 기계값은 `time` 요소의 속성으로만 읽을 수 있다.
    const late = change.querySelectorAll(`time[datetime="${LATE_AT}"]`)
    expect(late.length).toBeGreaterThanOrEqual(2)
    expect(change).toHaveTextContent(copy.firstAffectedHelp)
    // Never the claim that the whole history was read again.
    expect(change.textContent).not.toMatch(/전체 이력을 다시 읽었/)
  })

  it('shows the six metrics before and after, and says the basis time moved too', () => {
    renderChange()
    const change = part()
    expect(change).toHaveTextContent(copy.metrics)
    expect(change).toHaveTextContent(
      copy.metricsHelp(
        formatSeoulTime(PREVIOUS_BASIS, planResultContent.timeUnavailable),
        formatSeoulTime(v2().basisAt as string, planResultContent.timeUnavailable),
      ),
    )
    const lists = within(change).getAllByRole('list')
    const metrics = lists.find((list) =>
      within(list).queryByText(planResultContent.metrics.CURRENT_VALUE.name),
    )
    if (metrics === undefined) throw new Error('no metric list')
    const items = within(metrics).getAllByRole('listitem')
    expect(items).toHaveLength(6)
    expect(items[0]).toHaveTextContent(`${copy.previousValue}1,225.00 USD`)
    expect(items[0]).toHaveTextContent(`${copy.newValue}1,250.00 USD`)
    expect(items[4]).toHaveTextContent('7.00%')
  })

  it('lists each changed cash flow with its Bybit id, time, type, USD before and after, price evidence and times', () => {
    renderChange()
    const change = part()
    const title = copy.eventTitle(copy.eventKinds.ADDED ?? 'missing', 'late-event-01')
    const item = within(change)
      .getAllByRole('listitem')
      .find((entry) => within(entry).queryByText(title) !== null)
    if (item === undefined) throw new Error('no event item')
    expect(item).toHaveTextContent(copy.eventNotes.lateAdded)
    expect(item).toHaveTextContent('late-event-01')
    // The type in words, the history's own name for it; the raw Bybit type is not shown.
    expect(item).toHaveTextContent(
      `${copy.transactionType}${adjustmentHistoryContent.filter.options.IN}`,
    )
    expect(item).not.toHaveTextContent('TRANSFER_IN')
    expect(item).toHaveTextContent(`${ABSENT} ${copy.arrow} ${formatAmount('25')} USD`)
    expect(item).toHaveTextContent(`${ABSENT} ${copy.arrow} ${copy.estimatedValues.false}`)
    expect(item).toHaveTextContent(copy.evidenceTypes.DIRECT_NORMALIZATION)
    expect(item).toHaveTextContent(copy.discoveredAt)
    expect(item).toHaveTextContent(copy.appliedAt)
  })

  it('a value change shows both USD values rounded once, and an unknown code folds to safe copy', () => {
    renderChange(
      evidence(
        v2Evidence({
          kind: 'SOMETHING_NEW',
          reason: 'NEW_REASON',
          events: [
            lateEvent({
              changeKind: 'VALUE_CHANGED',
              late: false,
              transactionType: 'NEW_TYPE',
              previousUsdValue: '24.994',
              previousEstimated: true,
              usdValue: '25.005',
              estimated: true,
              evidenceType: 'NEW_EVIDENCE',
            }),
          ],
        }),
      ),
    )
    const change = part()
    expect(change).toHaveTextContent(copy.unknownKind)
    expect(change).toHaveTextContent(planVerificationContent.unknownJobReason)
    expect(change).toHaveTextContent(`24.99 USD ${copy.arrow} 25.01 USD`)
    expect(change).toHaveTextContent(copy.eventNotes.valueChanged)
    expect(change).toHaveTextContent(copy.unknownEvidenceType)
    expect(change).toHaveTextContent(copy.unknownTransactionType)
    expect(change.textContent).not.toMatch(/SOMETHING_NEW|NEW_REASON|NEW_EVIDENCE|NEW_TYPE/)
  })

  it('says what each changed flow is in its own words: only an added flow is new or found late', () => {
    renderChange(
      evidence(
        v2Evidence({
          events: [
            lateEvent(),
            lateEvent({
              sourceId: 'new-event-02',
              occurredAt: '2026-09-26T12:00:00Z',
              late: false,
            }),
            lateEvent({
              sourceId: 'changed-event-03',
              changeKind: 'VALUE_CHANGED',
              late: true,
              previousUsdValue: '9.5',
              previousEstimated: true,
              usdValue: '9.75',
              estimated: true,
            }),
            lateEvent({
              sourceId: 'removed-event-04',
              changeKind: 'REMOVED',
              late: true,
              transactionType: 'TRANSFER_OUT',
              previousUsdValue: '-5',
              previousEstimated: false,
              usdValue: null,
              estimated: null,
            }),
            lateEvent({ sourceId: 'odd-event-05', changeKind: 'SOMETHING_NEW' }),
          ],
        }),
      ),
    )
    const notes = copy.eventNotes
    expect(eventItem('late-event-01')).toHaveTextContent(notes.lateAdded)
    expect(eventItem('new-event-02')).toHaveTextContent(notes.added)
    expect(eventItem('new-event-02')).not.toHaveTextContent(notes.lateAdded)
    // A late value change is not "a new event", and not "found late and added".
    expect(eventItem('changed-event-03')).toHaveTextContent(notes.valueChanged)
    expect(eventItem('changed-event-03')).not.toHaveTextContent(notes.added)
    expect(eventItem('changed-event-03')).not.toHaveTextContent(notes.lateAdded)
    expect(eventItem('removed-event-04')).toHaveTextContent(notes.removed)
    expect(eventItem('removed-event-04')).toHaveTextContent(
      `${copy.transactionType}${adjustmentHistoryContent.filter.options.OUT}`,
    )
    expect(eventItem('removed-event-04')).toHaveTextContent(`-5.00 USD ${copy.arrow} ${ABSENT}`)
    expect(eventItem('odd-event-05')).toHaveTextContent(notes.unknown)
    expect(part().textContent).not.toMatch(/TRANSFER_IN|TRANSFER_OUT|SOMETHING_NEW/)
  })

  it('compares the previous basis with the shown result’s basis, the same kind of field', () => {
    const shownBasis = '2026-09-27T01:00:00Z'
    render(
      <EvidenceChange
        basisAt={shownBasis}
        evidence={{ ...evidence(), times: { ...evidence().times, tc: '2026-01-01T00:00:00Z' } }}
      />,
    )
    expect(part()).toHaveTextContent(
      copy.metricsHelp(
        formatSeoulTime(PREVIOUS_BASIS, planResultContent.timeUnavailable),
        formatSeoulTime(shownBasis, planResultContent.timeUnavailable),
      ),
    )
  })

  it('the first official version says there is nothing to compare, and lists no metric', () => {
    const body = calculationEvidenceFixture(evidenceResultFixture())
    const parsed = parseEvidence(body, shown(evidenceResultFixture()))
    if (parsed === null) throw new Error('the fixture is not whole')
    render(<EvidenceChange basisAt={BASIS_AT} evidence={parsed} />)
    const change = part()
    expect(change).toHaveTextContent(copy.first)
    expect(change).toHaveTextContent(copy.noEvents)
    expect(change).toHaveTextContent(`${copy.purpose}${ABSENT}`)
    expect(change).toHaveTextContent(`${copy.firstAffected}${ABSENT}`)
    expect(change).not.toHaveTextContent(copy.metrics)
    expect(getObservedStorageEntries()).toEqual([])
  })

  it('a later version without a late change is not called the first one', () => {
    renderChange(evidence(v2Evidence({ kind: 'NONE', purpose: 'SYNC', reason: null, events: [] })))
    expect(part()).toHaveTextContent(copy.kinds.NONE ?? 'missing')
    expect(part()).not.toHaveTextContent(copy.first)
  })

  it('uses no forbidden wording', () => {
    renderChange()
    expect(part().textContent).not.toMatch(
      /실시간|정확|보장|성공|실패|축하|손실|수익을 내|투자성과|계좌 수익률|추천합니다|스트릭|배지|달성하세요|서두르|놓치/,
    )
  })

  describe('parseEvidence: the change part is accepted whole, and only against the shown bundle', () => {
    it.each([
      ['no change part', { change: undefined }],
      ['a new metric that is not the shown one', 'metric'],
      ['a previous version outside the official list', 'previous'],
      ['a previous version that is the shown one', 'self'],
      ['a later version without a previous one', 'orphan'],
      ['a previous version with a basis after the shown one', 'basis'],
      ['a metric side with both a value and a reason', 'both'],
      ['an event without its Bybit id', 'event'],
      ['a range that ends before it starts', 'range'],
      ['an added flow with a previous value', 'addedBefore'],
      ['a changed value without its previous value', 'changedAlone'],
      ['a removed flow with a new value', 'removedAfter'],
      ['a value without its character', 'character'],
      ['a deposit whose previous value is negative', 'sign'],
      ['a withdrawal whose value is positive', 'outSign'],
      ['a flow discovered after it was applied', 'discovered'],
      ['the same Bybit id twice', 'twice'],
      ['a verification without change that names an impact start', 'verifiedStart'],
      ['a verification without change that lists a late flow', 'verifiedLate'],
      ['an impact start after the shown basis time', 'afterBasis'],
    ])('%s drops the whole lineage', (_name, broken) => {
      const result = v2()
      const metrics = comparedMetrics(result)
      const variants: Record<string, Record<string, unknown>> = {
        metric: { metrics: metrics.map((row, i) => (i === 1 ? { ...row, value: '1' } : row)) },
        previous: {
          previous: { pointerVersion: 1, calculationId: 'elsewhere', basisAt: PREVIOUS_BASIS },
        },
        self: {
          previous: {
            pointerVersion: 2,
            calculationId: EVIDENCE_IDS.newerCalculation,
            basisAt: PREVIOUS_BASIS,
          },
        },
        orphan: { previous: null, metrics: [], events: [], kind: 'NONE' },
        basis: {
          previous: {
            pointerVersion: 1,
            calculationId: EVIDENCE_IDS.calculation,
            basisAt: '2026-09-27T01:00:01Z',
          },
        },
        both: {
          metrics: metrics.map((row, i) =>
            i === 0 ? { ...row, previousUnavailableReason: 'CURRENT_VALUE_NOT_POSITIVE' } : row,
          ),
        },
        event: { events: [lateEvent({ sourceId: '' })] },
        range: { targets: [{ kind: 'NEW', start: result.basisAt, end: PREVIOUS_BASIS }] },
        addedBefore: { events: [lateEvent({ previousUsdValue: '20', previousEstimated: false })] },
        changedAlone: { events: [lateEvent({ changeKind: 'VALUE_CHANGED' })] },
        removedAfter: {
          events: [
            lateEvent({ changeKind: 'REMOVED', previousUsdValue: '25', previousEstimated: false }),
          ],
        },
        character: { events: [lateEvent({ estimated: null })] },
        sign: {
          events: [
            lateEvent({
              changeKind: 'VALUE_CHANGED',
              previousUsdValue: '-25',
              previousEstimated: false,
            }),
          ],
        },
        outSign: { events: [lateEvent({ transactionType: 'TRANSFER_OUT' })] },
        discovered: { events: [lateEvent({ discoveredAt: '2026-09-27T01:00:02Z' })] },
        twice: {
          events: [
            lateEvent(),
            lateEvent({
              changeKind: 'VALUE_CHANGED',
              late: false,
              previousUsdValue: '20',
              previousEstimated: false,
            }),
          ],
        },
        verifiedStart: { kind: 'VERIFIED_NO_CHANGE', events: [] },
        verifiedLate: { kind: 'VERIFIED_NO_CHANGE', firstAffectedAt: null },
        afterBasis: { firstAffectedAt: '2026-09-27T01:00:01Z' },
      }
      const body =
        typeof broken === 'string' ? v2Evidence(variants[broken]) : { ...v2Evidence(), ...broken }
      expect(parseEvidence(body, shown())).toBeNull()
    })

    it('a verification without change may list flows that are new since the last check', () => {
      const body = v2Evidence({
        kind: 'VERIFIED_NO_CHANGE',
        firstAffectedAt: null,
        events: [lateEvent({ late: false, occurredAt: '2026-09-26T12:00:00Z' })],
      })
      expect(parseEvidence(body, shown())?.change.kind).toBe('VERIFIED_NO_CHANGE')
    })

    it('the first official version is neither a correction nor a verification, and lists no flow', () => {
      const first = evidenceResultFixture()
      const base = calculationEvidenceFixture(first)
      for (const change of [
        evidenceChangeFixture({ kind: 'CORRECTED' }),
        evidenceChangeFixture({ kind: 'VERIFIED_NO_CHANGE' }),
        evidenceChangeFixture({ events: [lateEvent()] }),
      ]) {
        expect(parseEvidence({ ...base, change }, shown(first))).toBeNull()
      }
      expect(parseEvidence(base, shown(first))).not.toBeNull()
    })

    it('the previous version is the one right before the shown one, never an earlier one', () => {
      const v3 = evidenceResultFixture({ pointerVersion: 3, calculationId: THIRD, flowCount: 1 })
      const history = (previous: Record<string, unknown>): Record<string, unknown> =>
        calculationEvidenceFixture(v3, {
          versions: {
            official: [
              {
                pointerVersion: 1,
                calculationId: EVIDENCE_IDS.calculation,
                publishedAt: '2026-09-25T01:00:01Z',
                appliedUntil: '2026-09-26T01:00:01Z',
                current: false,
              },
              {
                pointerVersion: 2,
                calculationId: EVIDENCE_IDS.newerCalculation,
                publishedAt: '2026-09-26T01:00:01Z',
                appliedUntil: v3.publishedAt,
                current: false,
              },
              {
                pointerVersion: 3,
                calculationId: THIRD,
                publishedAt: v3.publishedAt,
                appliedUntil: null,
                current: true,
              },
            ],
            unpublished: [],
          },
          change: evidenceChangeFixture({
            kind: 'NONE',
            purpose: 'SYNC',
            previous,
            metrics: comparedMetrics(v3),
          }),
        })
      const shownV3 = shown(v3)
      expect(
        parseEvidence(
          history({
            pointerVersion: 1,
            calculationId: EVIDENCE_IDS.calculation,
            basisAt: PREVIOUS_BASIS,
          }),
          shownV3,
        ),
      ).toBeNull()
      expect(
        parseEvidence(
          history({
            pointerVersion: 2,
            calculationId: EVIDENCE_IDS.newerCalculation,
            basisAt: PREVIOUS_BASIS,
          }),
          shownV3,
        ),
      ).not.toBeNull()
    })

    it('a compared side reads null and absent alike', () => {
      const result = v2()
      // The reasons of two defined sides left out, rather than sent as null.
      const metrics = comparedMetrics(result).map((row) => ({
        metric: row.metric,
        previousValue: row.previousValue,
        value: row.value,
      }))
      expect(parseEvidence(v2Evidence({ metrics }), shown())).not.toBeNull()
    })

    it('keeps the evidence of v2 with its change', () => {
      expect(evidence().change).toMatchObject({
        kind: 'CORRECTED',
        previous: { pointerVersion: 1 },
        firstAffectedAt: LATE_AT,
        events: [{ sourceId: 'late-event-01', late: true }],
      })
      expect(calculationEvidenceContent.sections.snapshots).toBeDefined()
    })
  })
})
