/**
 * `GET /api/dashboard`의 응답 fixture(Story 2.8). 서버의 `DashboardController.DashboardView`와 같은
 * 모양이다. 값은 canonical decimal string이며 표시 반올림 전 값이다.
 *
 * 묶음은 스스로 일치해야 받아들여진다(`isPlanResult`): 축마다 자기 마지막 정상 시각이 그 묶음의
 * 시각이고, 신선도는 두 시각을 제 이름으로 되풀이하며, 계산 불가 지표는 값이 없는 지표 그대로다.
 * 아래 도우미가 그 일치를 기본값으로 지킨다.
 *
 * `READY`에는 동기화 블록(Story 2.11)이 함께 온다. 기본값은 "지금 동기화할 수 있고 지난 작업은 없다"이다.
 * 정밀 검증 블록과 버전 변경(Story 2.12)도 함께 온다. 기본값은 "지금 정밀 검증을 요청할 수 있고, 늦은 변경은
 * 없으며, 지난 검증 작업은 없다"이다. 계획 종료 블록(Story 2.13)의 기본값은 "지금 중도 종료할 수 있다"이다.
 *
 * `ENDED`(Story 2.13)는 중도 종료된 계획의 마지막 공식 묶음과 종료 기록이다. 기록은 묶음과 일치해야 받아들여진다
 * (`parsePlanEndRecord`): 예정 종료는 계획의 `T`, 마지막 정상 측정은 묶음의 측정 시각이다.
 */

import type { PlanEndStatus, SyncStatus, VerificationStatus } from '../session/useDashboard'
import type { DashboardSync } from '../session/useDashboardSync'
import type { PlanEnd } from '../session/usePlanEnd'
import type { ReauthenticationAccess } from '../session/useReauthentication'

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
    calculationKind: 'PROGRESS',
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

/** One sync job of `sync.latest` or of an admission answer (Story 2.11). In progress by default. */
export function syncJobFixture(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    jobId: '1f2e3d4c-5b6a-4978-8a9b-0c1d2e3f4a5b',
    purpose: 'SYNC',
    reason: null,
    trigger: 'MANUAL',
    status: 'QUEUED',
    stage: null,
    failureCode: null,
    admittedAt: '2026-09-27T01:10:00Z',
    finishedAt: null,
    ...overrides,
  }
}

/** The `sync` block of a `READY` answer (Story 2.11): a sync may be asked for now, and none ran before. */
export function syncFixture(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    availability: 'AVAILABLE',
    reason: null,
    retryAfterSeconds: null,
    latest: null,
    ...overrides,
  }
}

/**
 * The expected range of a precision verification (Story 2.12): the defaults of `verificationFixture`. The plan
 * started less than 7 days before its last check, so everything from t0 is read again and nothing is reused.
 */
export const PREVIEW: {
  readonly rangeStart: string
  readonly unconfirmedFrom: string
  readonly anomalyCount: number
  readonly reusedUntil: string | null
} = {
  rangeStart: '2026-09-25T00:00:00Z',
  unconfirmedFrom: '2026-09-25T00:00:00Z',
  anomalyCount: 0,
  reusedUntil: null,
}

/**
 * One job of `verification.latest` or of a verification admission answer (Story 2.12): a precision verification
 * the user asked for, in progress by default. Its ranges and counts are the latest job's details.
 */
export function verificationJobFixture(
  overrides: Record<string, unknown> = {},
): Record<string, unknown> {
  return {
    ...syncJobFixture({
      jobId: '2a3b4c5d-6e7f-4a8b-9c0d-1e2f3a4b5c6d',
      purpose: 'VERIFY',
      reason: 'USER_REQUEST',
      trigger: 'MANUAL',
    }),
    targets: [
      { kind: 'UNCONFIRMED', start: '2026-09-25T00:00:00Z', end: VERIFIED_THROUGH },
      { kind: 'NEW', start: VERIFIED_THROUGH, end: '2026-09-27T01:10:05Z' },
    ],
    reused: [],
    counts: { new: 0, same: 3, missing: 0, superseded: 0, conflict: 0 },
    ...overrides,
  }
}

/**
 * The `verification` block of a `READY` answer (Story 2.12): a precision verification may be asked for now,
 * with its expected range, and none ran before.
 */
export function verificationFixture(
  overrides: Record<string, unknown> = {},
): Record<string, unknown> {
  return {
    availability: 'AVAILABLE',
    reason: null,
    retryAfterSeconds: null,
    suggestedReason: 'USER_REQUEST',
    preview: { ...PREVIEW },
    catchUpFrom: null,
    latest: null,
    ...overrides,
  }
}

/**
 * The `change` block of a `READY` answer (Story 2.12): no late change by default. The total `lateChangeCount` is
 * the sum of the three counts unless given — a test of a contradiction gives it.
 */
export function changeFixture(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  const counts = {
    lateAddedCount: 0,
    valueChangedCount: 0,
    removedCount: 0,
    ...overrides,
  }
  const sum = [counts.lateAddedCount, counts.valueChangedCount, counts.removedCount].reduce<number>(
    (total, value) => total + (typeof value === 'number' ? value : 0),
    0,
  )
  return {
    kind: 'NONE',
    purpose: null,
    reason: null,
    lateChangeCount: sum,
    lateAddedCount: 0,
    valueChangedCount: 0,
    removedCount: 0,
    firstAffectedAt: null,
    verifiedAt: null,
    targets: [],
    reused: [],
    ...overrides,
  }
}

/** The `planEnd` block of a `READY` answer (Story 2.13): the plan may be ended early now. */
export function planEndFixture(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return { availability: 'AVAILABLE', reason: null, ...overrides }
}

/** A whole `READY` answer. */
export function dashboardFixture(
  result: Record<string, unknown> = planResultFixture(),
  nextAction = 'NONE',
  sync: Record<string, unknown> = syncFixture(),
  verification: Record<string, unknown> = verificationFixture(),
  change: Record<string, unknown> = changeFixture(),
  planEnd: Record<string, unknown> = planEndFixture(),
): Record<string, unknown> {
  return { state: 'READY', nextAction, result, sync, verification, change, planEnd }
}

/** The actual end time of the default ended plan (Story 2.13): after its last normal measurement. */
export const ENDED_AT = '2026-09-28T03:04:05.678Z'

/** The six metrics, as the freshness axis of an ended plan names them all. */
const ALL_METRICS = [
  'CURRENT_VALUE',
  'PLAN_AMOUNT',
  'PLAN_DIFFERENCE',
  'ADJUSTED_TARGET',
  'REQUIRED_RATE',
  'GOAL_PROGRESS',
]

/**
 * The `result` of an `ENDED` answer (Story 2.13): the default bundle of a plan ended early — `ENDED_EARLY`, its
 * freshness folded to `ENDED` (no longer synced, every metric affected, nothing to do).
 */
export function endedResultFixture(
  overrides: Record<string, unknown> = {},
): Record<string, unknown> {
  return planResultFixture({
    planStatus: 'ENDED_EARLY',
    trust: trustFixture({
      freshness: {
        state: 'ENDED',
        cause: 'PLAN_ENDED_EARLY',
        affectedMetrics: ALL_METRICS,
        nextAction: 'NONE',
      },
    }),
    ...overrides,
  })
}

/**
 * The `end` record of an `ENDED` answer (Story 2.13), made for the given result: an early end the user asked for by
 * default — no completion comparison, nothing uncollected, the delay from the last normal measurement.
 */
export function endRecordFixture(
  result: Record<string, unknown> = endedResultFixture(),
  overrides: Record<string, unknown> = {},
): Record<string, unknown> {
  const endedAt = (overrides.endedAt as string | undefined) ?? ENDED_AT
  const measuredAt = result.measuredAt as string
  return {
    reason: 'USER_REQUEST',
    endedAt,
    scheduledEndAt: (result.conditions as Record<string, unknown>).endAt,
    lastNormalMeasuredAt: measuredAt,
    lastNormalDelayMillis: Date.parse(endedAt) - Date.parse(measuredAt),
    completion: 'NOT_APPLICABLE',
    uncollectedFrom: null,
    uncollectedUntil: null,
    ...overrides,
  }
}

/** The `end` record of a plan ended after a long gap: the gap uncollected, the comparison not computable. */
export function longGapEndRecordFixture(
  result: Record<string, unknown> = endedResultFixture(),
  overrides: Record<string, unknown> = {},
): Record<string, unknown> {
  const endedAt = (overrides.endedAt as string | undefined) ?? ENDED_AT
  return endRecordFixture(result, {
    reason: 'LONG_GAP_NEW_PLAN',
    completion: 'UNAVAILABLE_DATA_GAP',
    uncollectedFrom: result.cashflowVerifiedThrough,
    uncollectedUntil: endedAt,
    ...overrides,
  })
}

/** A whole `ENDED` answer (Story 2.13): no sync, verification, change or plan-end block. */
export function endedDashboardFixture(
  result: Record<string, unknown> = endedResultFixture(),
  end: Record<string, unknown> = endRecordFixture(result),
): Record<string, unknown> {
  return { state: 'ENDED', nextAction: 'PREPARE_NEXT_PLAN', result, end }
}

/** The scheduled end `T` of the default plan (Story 2.14): its `conditions.endAt`. */
export const SCHEDULED_END_AT = '2026-10-25T04:00:00Z'

/** The first normal observation after `T` of the default completed plan: one hour after `T`. */
export const OBSERVED_AT = '2026-10-25T05:00:00Z'

/** The check after the observation that bounds the completion version's flows check (`U`). */
export const COMPLETION_VERIFIED_THROUGH = '2026-10-25T05:00:05Z'

/** The default first observed value, and the completion target of the default completion version. */
export const OBSERVED_VALUE = '1300'
export const COMPLETION_TARGET = '1331'

/**
 * The `result` of a `COMPLETED` answer whose pointer is still the last progress version (Story 2.14): the default
 * bundle, `COMPLETED`, its freshness folded to `COMPLETED` (every metric affected, nothing to do). This is the shape of
 * a plan whose `T` has passed before the first observation, and of a comparison still checking or held.
 */
export function completedProgressResultFixture(
  overrides: Record<string, unknown> = {},
): Record<string, unknown> {
  return planResultFixture({
    planStatus: 'COMPLETED',
    trust: trustFixture({
      freshness: {
        state: 'COMPLETED',
        cause: 'PLAN_COMPLETED',
        affectedMetrics: ALL_METRICS,
        nextAction: 'NONE',
      },
    }),
    ...overrides,
  })
}

/**
 * The `result` of a `COMPLETED` answer whose pointer is the completion version (Story 2.14): calculated at `T`, its
 * current value the first observation (measured at `OBSERVED_AT`), its plan amount the completion target, its required
 * rate not computable (no period left). When the comparison cannot be calculated (`comparable: false`), the plan
 * difference and the goal progress are not computable either.
 */
export function completionVersionResultFixture(
  options: { readonly comparable?: boolean; readonly observedAt?: string } = {},
  overrides: Record<string, unknown> = {},
): Record<string, unknown> {
  const comparable = options.comparable ?? true
  const measuredAt = options.observedAt ?? OBSERVED_AT
  const verifiedThrough = options.observedAt ?? COMPLETION_VERIFIED_THROUGH
  const unavailable = comparable
    ? ['REQUIRED_RATE']
    : ['PLAN_DIFFERENCE', 'REQUIRED_RATE', 'GOAL_PROGRESS']
  const notComparable = (metric: string): Record<string, unknown> => ({
    metric,
    nature: 'OFFICIAL',
    unavailableReason: 'COMPLETION_NOT_COMPARABLE',
  })
  return planResultFixture({
    planStatus: 'COMPLETED',
    pointerVersion: 2,
    calculationId: 'calculation-completion',
    calculationKind: 'COMPLETION',
    portfolioSnapshotId: 'portfolio-snapshot-observation',
    publishedAt: '2026-10-25T05:00:06Z',
    basisAt: SCHEDULED_END_AT,
    measuredAt,
    cashflowVerifiedThrough: verifiedThrough,
    trust: trustFixture({
      freshness: {
        state: 'COMPLETED',
        cause: 'PLAN_COMPLETED',
        lastNormalAt: measuredAt,
        measuredAt,
        cashflowVerifiedThrough: verifiedThrough,
        affectedMetrics: ALL_METRICS,
        nextAction: 'NONE',
      },
      inputReliability: { lastNormalAt: verifiedThrough },
      calculationNature: {
        state: 'NOT_COMPUTABLE',
        cause: 'METRIC_UNDEFINED',
        lastNormalAt: SCHEDULED_END_AT,
        affectedMetrics: unavailable,
        unavailableMetrics: unavailable,
      },
    }),
    metrics: [
      { metric: 'CURRENT_VALUE', value: OBSERVED_VALUE, nature: 'OFFICIAL' },
      { metric: 'PLAN_AMOUNT', value: COMPLETION_TARGET, nature: 'OFFICIAL' },
      comparable
        ? { metric: 'PLAN_DIFFERENCE', value: '-31', nature: 'OFFICIAL' }
        : notComparable('PLAN_DIFFERENCE'),
      { metric: 'ADJUSTED_TARGET', value: COMPLETION_TARGET, nature: 'OFFICIAL' },
      {
        metric: 'REQUIRED_RATE',
        nature: 'OFFICIAL',
        unavailableReason: 'REMAINING_PERIOD_NOT_POSITIVE',
      },
      comparable
        ? { metric: 'GOAL_PROGRESS', value: '0.6785566155122355868933', nature: 'OFFICIAL' }
        : notComparable('GOAL_PROGRESS'),
    ],
    ...overrides,
  })
}

/**
 * The `completion` record of a `COMPLETED` answer (Story 2.14), made for the given result. On a completion version
 * (calculated at `T`) it is confirmed by default, with the result's observation and target and no flow after `T`; on a
 * progress version it is still checking, with nothing observed yet. Overrides come last.
 */
export function completionRecordFixture(
  result: Record<string, unknown> = completedProgressResultFixture(),
  overrides: Record<string, unknown> = {},
): Record<string, unknown> {
  const scheduledEndAt = (result.conditions as Record<string, unknown>).endAt as string
  const completion = result.calculationKind === 'COMPLETION'
  const observedMeasuredAt = completion ? (result.measuredAt as string) : null
  const metrics = result.metrics as readonly Record<string, unknown>[]
  return {
    scheduledEndAt,
    comparison: completion ? 'CONFIRMED' : 'PENDING',
    reason: null,
    observedValue: completion ? metrics[0]?.value : null,
    observedMeasuredAt,
    observationDelayMillis:
      observedMeasuredAt === null
        ? null
        : Date.parse(observedMeasuredAt) - Date.parse(scheduledEndAt),
    completionTarget: completion ? metrics[1]?.value : null,
    postEndCashflowCount: completion ? 0 : null,
    // A sync runs the completion again while it is checking; a confirmed one is final.
    retryable: !completion,
    ...overrides,
  }
}

/** The observation part of a completion record observed at the given time with the given value, for a progress version. */
export function observedFixture(
  observedMeasuredAt: string = OBSERVED_AT,
  observedValue: string = OBSERVED_VALUE,
): Record<string, unknown> {
  return {
    observedValue,
    observedMeasuredAt,
    observationDelayMillis: Date.parse(observedMeasuredAt) - Date.parse(SCHEDULED_END_AT),
  }
}

/**
 * A whole `COMPLETED` answer (Story 2.14): the bundle, its completion record, the later value (none by default) and
 * the sync block (a sync may be asked for now by default) and the version change (none). The next action follows the
 * record: a sync for the observation exactly while a sync runs the completion again (`retryable`).
 */
export function completedDashboardFixture(
  result: Record<string, unknown> = completedProgressResultFixture(),
  completion: Record<string, unknown> = completionRecordFixture(result),
  extras: {
    readonly postEnd?: Record<string, unknown> | null
    readonly sync?: Record<string, unknown>
    readonly nextAction?: string
    readonly change?: Record<string, unknown>
  } = {},
): Record<string, unknown> {
  return {
    state: 'COMPLETED',
    nextAction:
      extras.nextAction ??
      (completion.retryable === true ? 'SYNC_FOR_OBSERVATION' : 'PREPARE_NEXT_PLAN'),
    result,
    completion,
    postEnd: extras.postEnd ?? null,
    sync: extras.sync ?? syncFixture(),
    change: extras.change ?? changeFixture(),
  }
}

/** The parsed default sync block, for views a test builds by hand. */
export const AVAILABLE_SYNC: SyncStatus = {
  availability: 'AVAILABLE',
  reason: null,
  retryAfterSeconds: null,
  latest: null,
}

/** The parsed default verification block, for views a test builds by hand. */
export const AVAILABLE_VERIFICATION: VerificationStatus = {
  availability: 'AVAILABLE',
  reason: null,
  retryAfterSeconds: null,
  suggestedReason: 'USER_REQUEST',
  preview: { ...PREVIEW },
  catchUpFrom: null,
  latest: null,
}

/** The parsed default plan-end block, for views a test builds by hand (Story 2.13). */
export const AVAILABLE_PLAN_END: PlanEndStatus = { availability: 'AVAILABLE', reason: null }

/** An end flow with nothing to say: for panels a test renders without the end hook (Story 2.13). */
export function idlePlanEnd(overrides: Partial<PlanEnd> = {}): PlanEnd {
  return {
    preparing: false,
    dialog: null,
    busy: false,
    notice: null,
    focus: null,
    request: () => undefined,
    confirm: () => undefined,
    cancel: () => undefined,
    ...overrides,
  }
}

/** A reauthentication that has not started: for panels a test renders without the hook. */
export function idleReauthentication(
  overrides: Partial<ReauthenticationAccess> = {},
): ReauthenticationAccess {
  return { pending: false, requestReauthentication: () => undefined, ...overrides }
}

/** A sync with nothing to say: for panels a test renders without the sync hook. */
export function idleSyncState(overrides: Partial<DashboardSync> = {}): DashboardSync {
  return {
    phase: 'idle',
    purpose: 'SYNC',
    running: false,
    followingElsewhere: false,
    canRequest: true,
    cooldownSeconds: null,
    canVerify: true,
    canCatchUp: false,
    admitting: false,
    verificationCooldownSeconds: null,
    focus: null,
    request: () => false,
    requestVerification: () => undefined,
    ...overrides,
  }
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

/**
 * `GET /api/dashboard/evidence`의 응답 fixture(Story 2.9). 서버의 `DashboardController.EvidenceView`와 같은
 * 모양이며, 주어진 대시보드 결과의 참조·시각·스냅샷·지표 성격에 맞춰 만든다 — 근거는 지금 보이는 묶음과
 * 일치할 때만 받아들여진다(`parseEvidence`).
 */
export function calculationEvidenceFixture(
  result: Record<string, unknown> = planResultFixture(),
  overrides: Record<string, unknown> = {},
): Record<string, unknown> {
  const metrics = result.metrics as readonly Record<string, unknown>[]
  const estimated = result.estimatedFlowCount as number
  return {
    planId: result.planId,
    pointerVersion: result.pointerVersion,
    calculationId: result.calculationId,
    snapshots: {
      portfolioSnapshotId: result.portfolioSnapshotId,
      portfolioSequence: 2,
      basisPortfolioSnapshotId: result.basisPortfolioSnapshotId,
      basisSourceSnapshotId: '1e4d3b5f-0c2e-4d9f-b8a7-6e3c2f1d0b94',
      sourceSnapshotId: '0f3c2a4e-9b1d-4c8e-a7f6-5d2b1e0c9a83',
    },
    times: {
      t0: (result.conditions as Record<string, unknown>).startAt,
      tc: result.basisAt,
      measuredAt: result.measuredAt,
      cashflowVerifiedThrough: result.cashflowVerifiedThrough,
    },
    inputs: {
      connection: {
        provider: 'BYBIT',
        connectionId: '6a1f0c2e-3d4b-4e5f-8a9b-0c1d2e3f4a5b',
        policyVersion: 'bybit-readonly-uta-1.0',
      },
      sourceRange: {
        rangeStart: (result.conditions as Record<string, unknown>).startAt,
        rangeEnd: result.basisAt,
        // The first publication reads from t0 (Story 2.11 names the window start of every version).
        windowStart: (result.conditions as Record<string, unknown>).startAt,
        collectionPolicy: 'bybit-readonly-uta-1.0',
        completenessHash: 'c'.repeat(64),
      },
      classification: {
        classificationId: '7b2e1d3f-4c5a-4b6d-9e8f-1a2b3c4d5e6f',
        policyVersion: 'BYBIT-CASHFLOW-1.0',
        inputScopeHash: 'd'.repeat(64),
      },
      conversion: {
        conversionId: '8c3f2e4a-5d6b-4c7e-af90-2b3c4d5e6f7a',
        pricePolicy: 'BYBIT-PRICE-1.0',
        resultFingerprint: 'e'.repeat(64),
      },
      cashflows: { appliedCount: result.flowCount, estimatedCount: estimated },
      plan: { planId: result.planId, conditions: result.conditions },
    },
    calculation: {
      calculationId: result.calculationId,
      calculationKind: result.calculationKind,
      calculationPolicy: 'CALC-1.0',
      calendarPolicy: 'CALENDAR-SEOUL-1.0',
      displayPolicy: 'DISPLAY-1.0',
      precisionPolicy: 'DECIMAL128-HALF_EVEN',
      timeZone: 'Asia/Seoul',
      implementationVersion: 'bokrilog-calc-1.0.0+big-math-2.3.2',
      inputHash: 'a'.repeat(64),
      resultHash: 'b'.repeat(64),
      createdAt: result.publishedAt,
    },
    metrics: metrics.map((metric) => ({
      metric: metric.metric,
      nature: metric.nature,
      ...(metric.unavailableReason === undefined
        ? {}
        : { unavailableReason: metric.unavailableReason }),
      // A defined derived metric of an estimated version names the cause; an undefined one has only its reason.
      ...(metric.nature === 'ESTIMATED' && metric.unavailableReason === undefined
        ? { estimatedCause: { evidenceType: 'SPOT_KLINE_CLOSE', flowCount: estimated } }
        : {}),
    })),
    versions: {
      official: [
        {
          pointerVersion: result.pointerVersion,
          calculationId: result.calculationId,
          publishedAt: result.publishedAt,
          appliedUntil: null,
          current: true,
        },
      ],
      unpublished: [],
    },
    // The first official version has nothing to compare with (Story 2.12).
    change: evidenceChangeFixture(),
    ...overrides,
  }
}

/**
 * The change part of the evidence (Story 2.12): the first official version by default, with no previous one. Its
 * three counts are those of the given changed flows unless given — a test of a contradiction gives them.
 */
export function evidenceChangeFixture(
  overrides: Record<string, unknown> = {},
): Record<string, unknown> {
  const events = Array.isArray(overrides.events)
    ? (overrides.events as Record<string, unknown>[])
    : []
  const of = (match: (event: Record<string, unknown>) => boolean): number =>
    events.filter(match).length
  return {
    kind: 'NONE',
    purpose: null,
    reason: null,
    lateAddedCount: of((event) => event.changeKind === 'ADDED' && event.late === true),
    valueChangedCount: of((event) => event.changeKind === 'VALUE_CHANGED'),
    removedCount: of((event) => event.changeKind === 'REMOVED'),
    targets: [],
    reused: [],
    previous: null,
    firstAffectedAt: null,
    metrics: [],
    events: [],
    ...overrides,
  }
}

/** The basis time of the official version right before a later one (`laterEvidenceFixture`). */
export const PREVIOUS_BASIS_AT = '2026-09-26T01:00:00Z'

/**
 * The six compared metrics of a change part (Story 2.12): the given result's metrics as the new side, and the
 * previous version's — the same values unless given, one per metric in the screen's order.
 */
export function comparedMetricsFixture(
  result: Record<string, unknown>,
  previous?: readonly (string | null)[],
): Record<string, unknown>[] {
  return (result.metrics as Record<string, unknown>[]).map((metric, index) => {
    const before = previous === undefined ? (metric.value ?? null) : (previous[index] ?? null)
    return {
      metric: metric.metric,
      previousValue: before,
      previousUnavailableReason: before === null ? (metric.unavailableReason ?? 'X') : null,
      value: metric.value ?? null,
      unavailableReason: metric.unavailableReason ?? null,
    }
  })
}

/**
 * The evidence of a later official version (Story 2.11·2.12): the given result as the current version, the
 * version right before it in the official list, and a change part that names that version with the six compared
 * metrics and no changed flow — the change of a sync without a late change. Overrides come last.
 */
export function laterEvidenceFixture(
  result: Record<string, unknown>,
  overrides: Record<string, unknown> = {},
): Record<string, unknown> {
  const pointer = result.pointerVersion as number
  const previousId =
    result.calculationId === EVIDENCE_IDS.calculation
      ? EVIDENCE_IDS.newerCalculation
      : EVIDENCE_IDS.calculation
  return calculationEvidenceFixture(result, {
    versions: {
      official: [
        {
          pointerVersion: pointer - 1,
          calculationId: previousId,
          publishedAt: '2026-09-26T01:00:01Z',
          appliedUntil: result.publishedAt,
          current: false,
        },
        {
          pointerVersion: pointer,
          calculationId: result.calculationId,
          publishedAt: result.publishedAt,
          appliedUntil: null,
          current: true,
        },
      ],
      unpublished: [],
    },
    change: evidenceChangeFixture({
      purpose: 'SYNC',
      previous: {
        pointerVersion: pointer - 1,
        calculationId: previousId,
        basisAt: PREVIOUS_BASIS_AT,
      },
      metrics: comparedMetricsFixture(result),
    }),
    ...overrides,
  })
}

/** UUID-shaped ids, as a real server sends them — a `plan-1` would be a malformed reference there. */
export const EVIDENCE_IDS = {
  plan: '5d1e7c2a-8b3f-4e6a-9c0d-1f2e3a4b5c6d',
  calculation: '9e8d7c6b-5a4f-4e3d-8c2b-1a0f9e8d7c6b',
  newerCalculation: '4a5b6c7d-8e9f-4a0b-9c1d-2e3f4a5b6c7d',
  snapshot: '3c4d5e6f-7a8b-4c9d-8e0f-1a2b3c4d5e6f',
  basis: '2b3c4d5e-6f7a-4b8c-9d0e-1f2a3b4c5d6e',
} as const

/** A dashboard result whose ids are UUID-shaped, for the calculation evidence tests. */
export function evidenceResultFixture(
  overrides: Record<string, unknown> = {},
): Record<string, unknown> {
  return planResultFixture({
    planId: EVIDENCE_IDS.plan,
    calculationId: EVIDENCE_IDS.calculation,
    portfolioSnapshotId: EVIDENCE_IDS.snapshot,
    basisPortfolioSnapshotId: EVIDENCE_IDS.basis,
    ...overrides,
  })
}

/** UUID-shaped ids the adjustment history fixtures cite. */
export const ADJUSTMENT_IDS = {
  classification: '7b2e1d3f-4c5a-4b6d-9e8f-1a2b3c4d5e6f',
  conversion: '8c3f2e4a-5d6b-4c7e-af90-2b3c4d5e6f7a',
  earlierConversion: '6d7e8f90-1a2b-4c3d-9e4f-5a6b7c8d9e0f',
} as const

/** The event time of the fixture row with this sequence: one second apart, in order. */
export function adjustmentTime(sequence: number): string {
  return new Date(Date.parse('2026-09-26T00:00:00Z') + sequence * 1000)
    .toISOString()
    .replace('.000Z', 'Z')
}

/**
 * One row of `GET /api/dashboard/adjustments` (Story 2.10), the server's `AdjustmentRowView`: a directly
 * normalized deposit of `10.25 USDT` by default, citing the given calculation. Values are canonical decimal
 * strings before display rounding.
 */
export function adjustmentRowFixture(
  sequence: number,
  calculationId: string,
  overrides: Record<string, unknown> = {},
): Record<string, unknown> {
  const id = String(sequence).padStart(2, '0')
  return {
    sequence,
    occurredAt: adjustmentTime(sequence),
    direction: 'IN',
    asset: 'USDT',
    signedQuantity: '10.25',
    usdValue: '10.25',
    classification: {
      transactionType: 'TRANSFER_IN',
      ruleId: 'EXTERNAL_TRANSFER_IN',
      policyVersion: 'BYBIT-CASHFLOW-1.0',
    },
    price: {
      policyVersion: 'BYBIT-PRICE-1.0',
      evidenceType: 'DIRECT_NORMALIZATION',
      estimated: false,
      unitPrice: '1',
      routeId: 'DIRECT',
      legs: [],
    },
    discoveredAt: '2026-09-27T00:59:30Z',
    refs: {
      sourceEventId: `0a1b2c3d-4e5f-4a6b-8c7d-0000000000${id}`,
      sourceId: `adj-${id}`,
      classificationId: ADJUSTMENT_IDS.classification,
      conversionId: ADJUSTMENT_IDS.conversion,
      calculationId,
    },
    changed: false,
    revisions: [],
    ...overrides,
  }
}

/** A withdrawal row of `-2.5 USDT`. */
export function adjustmentWithdrawalFixture(
  sequence: number,
  calculationId: string,
  overrides: Record<string, unknown> = {},
): Record<string, unknown> {
  return adjustmentRowFixture(sequence, calculationId, {
    direction: 'OUT',
    signedQuantity: '-2.5',
    usdValue: '-2.5',
    classification: {
      transactionType: 'TRANSFER_OUT',
      ruleId: 'EXTERNAL_TRANSFER_OUT',
      policyVersion: 'BYBIT-CASHFLOW-1.0',
    },
    ...overrides,
  })
}

/**
 * The exact sum of canonical decimal strings, in canonical notation — the way the server adds the stored
 * values (never through `number`). `"0.005" × 3` is `"0.015"`.
 */
export function exactDecimalSum(values: readonly string[]): string {
  const scale = Math.max(0, ...values.map((value) => value.split('.')[1]?.length ?? 0))
  const total = values.reduce((sum, value) => {
    const negative = value.startsWith('-')
    const [whole = '0', fraction = ''] = (negative ? value.slice(1) : value).split('.')
    const units = BigInt(`${whole}${fraction.padEnd(scale, '0')}`)
    return sum + (negative ? -units : units)
  }, 0n)
  const negative = total < 0n
  const digits = (negative ? -total : total).toString().padStart(scale + 1, '0')
  const whole = digits.slice(0, digits.length - scale)
  const fraction = digits.slice(digits.length - scale).replace(/0+$/, '')
  const shown = fraction === '' ? whole : `${whole}.${fraction}`
  return negative && shown !== '0' ? `-${shown}` : shown
}

/**
 * One page of `GET /api/dashboard/adjustments`, the server's `AdjustmentHistoryView`, made for the given
 * dashboard result: its reference, publication time and verified range. Without a summary of its own, the
 * summary is the rows' count and the exact sum of their USD values, with nothing excluded — so a fixture
 * never contradicts itself.
 */
export function adjustmentPageFixture(
  result: Record<string, unknown>,
  page: {
    readonly direction?: string
    readonly rows: readonly Record<string, unknown>[]
    readonly nextCursor?: string | null
    readonly summary?: Record<string, unknown>
  },
): Record<string, unknown> {
  return {
    planId: result.planId,
    pointerVersion: result.pointerVersion,
    calculationId: result.calculationId,
    direction: page.direction ?? 'ALL',
    appliedAt: result.publishedAt,
    coverage: {
      from: (result.conditions as Record<string, unknown>).startAt,
      through: result.cashflowVerifiedThrough,
    },
    summary: page.summary ?? {
      count: page.rows.length,
      netUsdValue: exactDecimalSum(page.rows.map((row) => row.usdValue as string)),
      excludedCount: 0,
    },
    rows: page.rows,
    nextCursor: page.nextCursor ?? null,
  }
}
