import { render, screen, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'

import { planResultContent as copy } from '../content/planResult'
import { isPlanResult, type PlanResult } from '../session/usePlanResult'
import { estimatedUndefinedFixture, planResultFixture } from '../test/planResultFixture'
import { PlanResultPanel } from './PlanResultPanel'

function parsed(body: Record<string, unknown>): PlanResult {
  expect(isPlanResult(body)).toBe(true)
  return body as unknown as PlanResult
}

const NAMES = [
  '현재가치',
  '현재 시점 계획금액',
  '계획 대비 차이',
  '조정 최종 목표',
  '남은 필요 등가수익률',
  '목표 수익 달성도',
]

describe('PlanResultPanel', () => {
  it('shows the six metrics in order, each as name, value, period and basis, then nature', () => {
    render(<PlanResultPanel result={parsed(planResultFixture())} />)

    const cards = within(screen.getByRole('region', { name: copy.metricsHeading })).getAllByRole(
      'article',
    )
    expect(cards.map((card) => within(card).getByRole('heading').textContent)).toEqual(NAMES)
    const values = ['1,250.00 USD', '1,210.00 USD', '40.00 USD', '1,331.00 USD', '6.48%', '75.53%']
    cards.forEach((card, index) => {
      const text = card.textContent
      const name = NAMES[index] ?? ''
      const value = values[index] ?? ''
      expect(text).toContain(value)
      expect(text.indexOf(name)).toBeLessThan(text.indexOf(value))
      expect(text.indexOf(value)).toBeLessThan(text.indexOf('일 주기 · 기준'))
      expect(text.indexOf('일 주기 · 기준')).toBeLessThan(text.indexOf(`${copy.natureLabel}: 공식`))
    })
    expect(screen.getByText(copy.requiredRateBoundary('일'))).toBeInTheDocument()
  })

  it('shows the plan conditions, the three times in Asia/Seoul and the three axes', () => {
    render(<PlanResultPanel result={parsed(planResultFixture())} />)

    const conditions = within(screen.getByRole('region', { name: copy.conditionsHeading }))
    expect(conditions.getByText('1,234.56 USD')).toBeInTheDocument()
    expect(conditions.getByText('매일')).toBeInTheDocument()
    expect(conditions.getByText('10%')).toBeInTheDocument()
    const times = within(screen.getByRole('region', { name: copy.timesHeading }))
    for (const label of [copy.basisAt, copy.measuredAt, copy.verifiedThrough]) {
      expect(times.getByText(label)).toBeInTheDocument()
    }
    const basis = new Intl.DateTimeFormat('ko-KR', {
      timeZone: 'Asia/Seoul',
      dateStyle: 'medium',
      timeStyle: 'short',
    }).format(new Date('2026-09-27T01:00:00Z'))
    expect(times.getAllByText(basis)[0]).toHaveAttribute('datetime', '2026-09-27T01:00:00Z')
    const trust = within(screen.getByRole('region', { name: copy.trustHeading }))
    expect(trust.getByText('신선도: 최신')).toBeInTheDocument()
    expect(trust.getByText('입력 신뢰도: 확인됨')).toBeInTheDocument()
    expect(trust.getByText('계산 성격: 공식')).toBeInTheDocument()
    // Each axis names the times it is judged from; none repeats one "last normal" time three times.
    const [freshness, reliability, nature] = within(
      screen.getByRole('region', { name: copy.trustHeading }),
    ).getAllByRole('listitem')
    expect(freshness).toHaveTextContent(copy.measuredAt)
    expect(freshness).toHaveTextContent(copy.verifiedThrough)
    expect(reliability).toHaveTextContent(copy.verifiedThrough)
    expect(reliability).not.toHaveTextContent(copy.measuredAt)
    expect(nature).toHaveTextContent(copy.basisAt)
    expect(screen.queryByText('마지막 정상 시각')).not.toBeInTheDocument()
    // An official plan's character reaches all six metrics.
    expect(nature).toHaveTextContent(
      `${copy.axes.impact}${copy.axes.nature.impacts.OFFICIAL ?? ''}`,
    )
  })

  it('keeps the character beside "not computable" instead of replacing it', () => {
    const undefinedRate = planResultFixture({
      trust: {
        freshness: {
          state: 'FRESH',
          measuredAt: '2026-09-27T00:59:59Z',
          cashflowVerifiedThrough: '2026-09-27T01:00:00Z',
        },
        inputReliability: { state: 'CONFIRMED' },
        calculationNature: {
          state: 'NOT_COMPUTABLE',
          character: 'OFFICIAL',
          unavailableMetrics: ['REQUIRED_RATE'],
        },
      },
      metrics: [
        { metric: 'CURRENT_VALUE', value: '0', nature: 'OFFICIAL' },
        { metric: 'PLAN_AMOUNT', value: '1210', nature: 'OFFICIAL' },
        { metric: 'PLAN_DIFFERENCE', value: '-1210', nature: 'OFFICIAL' },
        { metric: 'ADJUSTED_TARGET', value: '1331', nature: 'OFFICIAL' },
        {
          metric: 'REQUIRED_RATE',
          nature: 'OFFICIAL',
          unavailableReason: 'CURRENT_VALUE_NOT_POSITIVE',
        },
        { metric: 'GOAL_PROGRESS', value: '-2', nature: 'OFFICIAL' },
      ],
    })
    render(<PlanResultPanel result={parsed(undefinedRate)} />)

    const rate = within(screen.getByRole('region', { name: copy.metricsHeading })).getAllByRole(
      'article',
    )[4]
    expect(rate).toHaveTextContent(`${copy.natureLabel}: 공식`)
    expect(rate).toHaveTextContent(
      `${copy.notComputable}: ${copy.reasons.CURRENT_VALUE_NOT_POSITIVE ?? ''}`,
    )
    expect(rate).not.toHaveTextContent(`${copy.natureLabel}: ${copy.notComputable}`)
  })

  it('names the five estimated metrics as the estimated character’s reach', () => {
    const estimated = planResultFixture({
      flowCount: 1,
      estimatedFlowCount: 1,
      trust: {
        freshness: {
          state: 'FRESH',
          measuredAt: '2026-09-27T00:59:59Z',
          cashflowVerifiedThrough: '2026-09-27T01:00:00Z',
        },
        inputReliability: { state: 'CONFIRMED' },
        calculationNature: { state: 'ESTIMATED', character: 'ESTIMATED', unavailableMetrics: [] },
      },
      metrics: [
        { metric: 'CURRENT_VALUE', value: '1250', nature: 'OFFICIAL' },
        { metric: 'PLAN_AMOUNT', value: '1210', nature: 'ESTIMATED' },
        { metric: 'PLAN_DIFFERENCE', value: '40', nature: 'ESTIMATED' },
        { metric: 'ADJUSTED_TARGET', value: '1331', nature: 'ESTIMATED' },
        { metric: 'REQUIRED_RATE', value: '0.0648', nature: 'ESTIMATED' },
        { metric: 'GOAL_PROGRESS', value: '0.75', nature: 'ESTIMATED' },
      ],
    })
    render(<PlanResultPanel result={parsed(estimated)} />)

    const nature = within(screen.getByRole('region', { name: copy.trustHeading })).getAllByRole(
      'listitem',
    )[2]
    expect(nature).toHaveTextContent('계산 성격: 추정')
    expect(nature).toHaveTextContent(
      `${copy.axes.impact}${copy.axes.nature.impacts.ESTIMATED ?? ''}`,
    )
    expect(nature).not.toHaveTextContent(`${copy.axes.impact}${copy.axes.freshness.impact}`)
  })

  it('folds unknown state codes to safe copy and promises no automatic refresh', () => {
    const unknown = planResultFixture({
      conditions: {
        principal: '1234.56',
        startAt: '2026-09-25T00:00:00Z',
        endAt: '2026-10-25T04:00:00Z',
        period: 'FORTNIGHTLY',
        targetRate: '0.1',
      },
      trust: {
        freshness: {
          state: 'LAGGING_SOMEHOW',
          measuredAt: '2026-09-27T00:59:59Z',
          cashflowVerifiedThrough: '2026-09-27T01:00:00Z',
        },
        inputReliability: { state: 'QUESTIONABLE' },
        calculationNature: { state: 'MYSTERY', character: 'OFFICIAL', unavailableMetrics: [] },
      },
    })
    render(<PlanResultPanel result={parsed(unknown)} />)

    const text = document.body.textContent
    for (const code of ['LAGGING_SOMEHOW', 'QUESTIONABLE', 'MYSTERY', 'FORTNIGHTLY']) {
      expect(text).not.toContain(code)
    }
    expect(screen.getByText(`신선도: ${copy.axes.unknownState}`)).toBeInTheDocument()
    expect(screen.getByText(`입력 신뢰도: ${copy.axes.unknownState}`)).toBeInTheDocument()
    expect(screen.getByText(`계산 성격: ${copy.axes.unknownState}`)).toBeInTheDocument()
  })

  it('tells a syncing reader to reopen the screen rather than promising the result will change', () => {
    const syncing = planResultFixture({
      trust: {
        freshness: {
          state: 'SYNCING',
          measuredAt: '2026-09-27T00:59:59Z',
          cashflowVerifiedThrough: '2026-09-27T01:00:00Z',
        },
        inputReliability: { state: 'CONFIRMED' },
        calculationNature: { state: 'OFFICIAL', character: 'OFFICIAL', unavailableMetrics: [] },
      },
    })
    render(<PlanResultPanel result={parsed(syncing)} />)

    const freshness = within(screen.getByRole('region', { name: copy.trustHeading })).getAllByRole(
      'listitem',
    )[0]
    expect(freshness).toHaveTextContent('신선도: 동기화 중')
    expect(freshness).toHaveTextContent(copy.axes.freshness.actions.SYNCING ?? '')
    expect(freshness).toHaveTextContent('새로 열면')
    expect(freshness).not.toHaveTextContent('바뀝니다')
  })

  it('renders plan terms sent in a trailing-zero notation instead of dropping the whole result', () => {
    const notation = planResultFixture({
      conditions: {
        principal: '1250.00',
        startAt: '2026-09-25T00:00:00Z',
        endAt: '2026-10-25T04:00:00Z',
        period: 'DAILY',
        targetRate: '0.10',
      },
    })
    render(<PlanResultPanel result={parsed(notation)} />)

    const conditions = within(screen.getByRole('region', { name: copy.conditionsHeading }))
    expect(conditions.getByText('1,250.00 USD')).toBeInTheDocument()
    expect(conditions.getByText('10%')).toBeInTheDocument()
    // Only notation is tolerated: an exponent or a sign in the terms still rejects the result.
    expect(
      isPlanResult(
        planResultFixture({
          conditions: { ...(notation.conditions as object), principal: '1.25E3' },
        }),
      ),
    ).toBe(false)
  })

  it('never shows an undefined metric as zero and marks estimated metrics with their evidence', () => {
    render(<PlanResultPanel result={parsed(estimatedUndefinedFixture())} />)

    const cards = within(screen.getByRole('region', { name: copy.metricsHeading })).getAllByRole(
      'article',
    )
    const [current, plan, difference, target, rate, progress] = cards
    expect(current).toHaveTextContent('0.00 USD')
    expect(current).not.toHaveTextContent('-0.00')
    expect(current).toHaveTextContent(`${copy.natureLabel}: 공식`)
    expect(current).not.toHaveTextContent('추정 근거')
    expect(plan).toHaveTextContent('1,358.02 USD')
    expect(difference).toHaveTextContent('-1,358.02 USD')
    expect(target).toHaveTextContent('1,500.50 USD')
    expect(rate).toHaveTextContent(copy.notComputable)
    expect(rate).toHaveTextContent(copy.reasons.CURRENT_VALUE_NOT_POSITIVE ?? '')
    // The undefined ratio of an estimated plan is still estimated.
    expect(rate).toHaveTextContent(`${copy.natureLabel}: 추정`)
    expect(rate).not.toHaveTextContent(/0\.00%|0%/)
    expect(progress).toHaveTextContent('-456.79%')
    for (const card of [plan, difference, target, rate, progress]) {
      expect(card).toHaveTextContent(copy.estimatedEvidence(1))
    }
    const trust = within(screen.getByRole('region', { name: copy.trustHeading }))
    expect(trust.getByText('신선도: 최신 아님')).toBeInTheDocument()
    expect(trust.getByText('계산 성격: 계산 불가')).toBeInTheDocument()
    expect(trust.getByText(copy.axes.nature.estimatedToo)).toBeInTheDocument()
    expect(
      trust.getByText(copy.axes.nature.unavailableMetrics('남은 필요 등가수익률')),
    ).toBeInTheDocument()
  })

  it('has no live region of its own and no recommendation or performance wording', () => {
    render(<PlanResultPanel result={parsed(estimatedUndefinedFixture())} />)

    expect(screen.queryByRole('status')).not.toBeInTheDocument()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    const text = document.body.textContent
    expect(text).not.toMatch(
      /축하|성공|실패|손실|수익을 내|투자성과|계좌 수익률|추천합니다|스트릭|배지/,
    )
  })

  it('rejects a response that is missing an axis or a metric instead of rendering it in part', () => {
    const missingMetric = planResultFixture()
    missingMetric.metrics = (missingMetric.metrics as unknown[]).slice(0, 5)
    expect(isPlanResult(missingMetric)).toBe(false)
    const zeroForUndefined = planResultFixture({
      metrics: [
        { metric: 'CURRENT_VALUE', value: '1', nature: 'OFFICIAL' },
        { metric: 'PLAN_AMOUNT', value: '1', nature: 'OFFICIAL' },
        { metric: 'PLAN_DIFFERENCE', value: '1', nature: 'OFFICIAL' },
        { metric: 'ADJUSTED_TARGET', value: '1', nature: 'OFFICIAL' },
        { metric: 'REQUIRED_RATE', value: '0', nature: 'OFFICIAL', unavailableReason: 'X' },
        { metric: 'GOAL_PROGRESS', value: '1', nature: 'OFFICIAL' },
      ],
    })
    expect(isPlanResult(zeroForUndefined)).toBe(false)
    const exponent = planResultFixture()
    ;(exponent.metrics as Record<string, unknown>[])[1] = {
      metric: 'PLAN_AMOUNT',
      value: '1E3',
      nature: 'OFFICIAL',
    }
    expect(isPlanResult(exponent)).toBe(false)
    expect(isPlanResult({ ...planResultFixture(), trust: {} })).toBe(false)
  })
})
