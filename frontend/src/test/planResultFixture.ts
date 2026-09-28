/**
 * `GET /api/plans/active`의 응답 fixture(Story 2.7). 서버의 `PlanResultController.ResultView`와 같은
 * 모양이다. 값은 canonical decimal string이며 표시 반올림 전 값이다.
 */
export function planResultFixture(
  overrides: Record<string, unknown> = {},
): Record<string, unknown> {
  return {
    planId: 'plan-1',
    planStatus: 'ACTIVE',
    conditions: {
      principal: '1234.56',
      startAt: '2026-09-25T00:00:00Z',
      endAt: '2026-10-25T04:00:00Z',
      period: 'DAILY',
      targetRate: '0.1',
    },
    pointerVersion: 1,
    publishedAt: '2026-09-27T01:00:01Z',
    calculationId: 'calculation-1',
    portfolioSnapshotId: 'portfolio-snapshot-2',
    basisPortfolioSnapshotId: 'portfolio-snapshot-1',
    basisAt: '2026-09-27T01:00:00Z',
    measuredAt: '2026-09-27T00:59:59Z',
    cashflowVerifiedThrough: '2026-09-27T01:00:00Z',
    policies: {
      calculation: 'CALC-1.0',
      calendar: 'CALENDAR-SEOUL-1.0',
      display: 'DISPLAY-1.0',
      price: 'BYBIT-PRICE-1.0',
      implementation: 'bokrilog-calc-1.0.0+big-math-2.3.2',
    },
    inputHash: 'a'.repeat(64),
    resultHash: 'b'.repeat(64),
    flowCount: 0,
    estimatedFlowCount: 0,
    trust: {
      freshness: {
        state: 'FRESH',
        measuredAt: '2026-09-27T00:59:59Z',
        cashflowVerifiedThrough: '2026-09-27T01:00:00Z',
      },
      inputReliability: { state: 'CONFIRMED' },
      calculationNature: {
        state: 'OFFICIAL',
        character: 'OFFICIAL',
        unavailableMetrics: [],
        estimatedFlowCount: 0,
      },
    },
    metrics: [
      { metric: 'CURRENT_VALUE', value: '1250', nature: 'OFFICIAL' },
      { metric: 'PLAN_AMOUNT', value: '1210', nature: 'OFFICIAL' },
      { metric: 'PLAN_DIFFERENCE', value: '40', nature: 'OFFICIAL' },
      { metric: 'ADJUSTED_TARGET', value: '1331', nature: 'OFFICIAL' },
      { metric: 'REQUIRED_RATE', value: '0.0648', nature: 'OFFICIAL' },
      { metric: 'GOAL_PROGRESS', value: '0.755287009063444108761329', nature: 'OFFICIAL' },
    ],
    ...overrides,
  }
}

/** 추정 흐름 하나와 정의되지 않는 필요율, 반올림 경계 값을 함께 가진 결과. */
export function estimatedUndefinedFixture(): Record<string, unknown> {
  return planResultFixture({
    flowCount: 2,
    estimatedFlowCount: 1,
    trust: {
      freshness: {
        state: 'STALE',
        measuredAt: '2026-09-27T00:59:59Z',
        cashflowVerifiedThrough: '2026-09-27T01:00:00Z',
      },
      inputReliability: { state: 'CONFIRMED' },
      calculationNature: {
        state: 'NOT_COMPUTABLE',
        character: 'ESTIMATED',
        unavailableMetrics: ['REQUIRED_RATE'],
        estimatedFlowCount: 1,
      },
    },
    metrics: [
      { metric: 'CURRENT_VALUE', value: '-0.001', nature: 'OFFICIAL' },
      { metric: 'PLAN_AMOUNT', value: '1358.016', nature: 'ESTIMATED' },
      { metric: 'PLAN_DIFFERENCE', value: '-1358.017', nature: 'ESTIMATED' },
      { metric: 'ADJUSTED_TARGET', value: '1500.5', nature: 'ESTIMATED' },
      {
        metric: 'REQUIRED_RATE',
        nature: 'ESTIMATED',
        unavailableReason: 'CURRENT_VALUE_NOT_POSITIVE',
      },
      { metric: 'GOAL_PROGRESS', value: '-4.56789', nature: 'ESTIMATED' },
    ],
  })
}
