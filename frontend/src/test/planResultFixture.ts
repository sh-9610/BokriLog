/**
 * `GET /api/dashboard`의 응답 fixture(Story 2.8). 서버의 `DashboardController.DashboardView`와 같은
 * 모양이다. 값은 canonical decimal string이며 표시 반올림 전 값이다.
 *
 * 묶음은 스스로 일치해야 받아들여진다(`isPlanResult`): 축마다 자기 마지막 정상 시각이 그 묶음의
 * 시각이고, 신선도는 두 시각을 제 이름으로 되풀이하며, 계산 불가 지표는 값이 없는 지표 그대로다.
 * 아래 도우미가 그 일치를 기본값으로 지킨다.
 */

export const BASIS_AT = '2026-09-27T01:00:00Z'
export const MEASURED_AT = '2026-09-27T00:59:59Z'
export const VERIFIED_THROUGH = '2026-09-27T01:00:00Z'

export function freshnessAxis(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    state: 'FRESH',
    cause: 'BOTH_WITHIN_WINDOW',
    lastNormalAt: MEASURED_AT,
    measuredAt: MEASURED_AT,
    cashflowVerifiedThrough: VERIFIED_THROUGH,
    affectedMetrics: [],
    nextAction: 'NONE',
    ...overrides,
  }
}

export function reliabilityAxis(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    state: 'CONFIRMED',
    cause: 'INPUTS_VERIFIED',
    lastNormalAt: VERIFIED_THROUGH,
    affectedMetrics: [],
    nextAction: 'NONE',
    ...overrides,
  }
}

export function natureAxis(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    state: 'OFFICIAL',
    character: 'OFFICIAL',
    cause: 'NO_ESTIMATED_INPUT',
    lastNormalAt: BASIS_AT,
    affectedMetrics: [],
    unavailableMetrics: [],
    estimatedFlowCount: 0,
    nextAction: 'NONE',
    ...overrides,
  }
}

export function trustFixture(
  axes: {
    readonly freshness?: Record<string, unknown>
    readonly inputReliability?: Record<string, unknown>
    readonly calculationNature?: Record<string, unknown>
  } = {},
): Record<string, unknown> {
  return {
    freshness: freshnessAxis(axes.freshness),
    inputReliability: reliabilityAxis(axes.inputReliability),
    calculationNature: natureAxis(axes.calculationNature),
  }
}

/** The `result` of a `READY` answer. */
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
    basisAt: BASIS_AT,
    measuredAt: MEASURED_AT,
    cashflowVerifiedThrough: VERIFIED_THROUGH,
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
    trust: trustFixture(),
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

/** A whole `READY` answer. */
export function dashboardFixture(
  result: Record<string, unknown> = planResultFixture(),
  nextAction = 'NONE',
): Record<string, unknown> {
  return { state: 'READY', nextAction, result }
}

export const NO_PLAN_FIXTURE: Readonly<Record<string, unknown>> = {
  state: 'NO_PLAN',
  nextAction: 'CREATE_PLAN',
}

export const PREPARING_FIXTURE: Readonly<Record<string, unknown>> = {
  state: 'PREPARING',
  nextAction: 'WAIT_FOR_RESULT',
}

/** 추정 흐름 하나와 정의되지 않는 필요율, 반올림 경계 값을 함께 가진 결과. */
export function estimatedUndefinedFixture(): Record<string, unknown> {
  return planResultFixture({
    flowCount: 2,
    estimatedFlowCount: 1,
    trust: trustFixture({
      freshness: {
        state: 'STALE',
        cause: 'MEASUREMENT_OUTDATED',
        affectedMetrics: [
          'CURRENT_VALUE',
          'PLAN_AMOUNT',
          'PLAN_DIFFERENCE',
          'ADJUSTED_TARGET',
          'REQUIRED_RATE',
          'GOAL_PROGRESS',
        ],
      },
      calculationNature: {
        state: 'NOT_COMPUTABLE',
        character: 'ESTIMATED',
        cause: 'METRIC_UNDEFINED',
        affectedMetrics: [
          'PLAN_AMOUNT',
          'PLAN_DIFFERENCE',
          'ADJUSTED_TARGET',
          'REQUIRED_RATE',
          'GOAL_PROGRESS',
        ],
        unavailableMetrics: ['REQUIRED_RATE'],
        estimatedFlowCount: 1,
      },
    }),
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
