import { act, renderHook, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'

import { CSRF_HEADER } from '../content/auth'
import { DASHBOARD_PATH, planResultContent } from '../content/planResult'
import { planSyncContent } from '../content/planSync'
import { DASHBOARD_VERIFICATION_PATH, planVerificationContent } from '../content/planVerification'
import { formatSeoulTime } from '../format/decimal'
import {
  AVAILABLE_VERIFICATION,
  changeFixture,
  dashboardFixture,
  planResultFixture,
  syncFixture,
  syncJobFixture,
  verificationFixture,
  verificationJobFixture,
} from '../test/planResultFixture'
import {
  getObservedAllowedRequests,
  getObservedRequests,
  getObservedStorageEntries,
  stubDashboard,
  stubDashboardSync,
  stubDashboardVerification,
} from '../test/setup'
import {
  dashboardAnnouncement,
  heldBy,
  inLongGap,
  NO_CHANGE,
  parseDashboard,
  parseSyncJob,
  parseVerificationStatus,
  parseVersionChange,
  UNKNOWN_VERIFICATION,
  useDashboard,
  type Dashboard,
  type DashboardView,
} from './useDashboard'
import {
  parseVerificationAdmission,
  SYNC_POLL_INTERVAL_MS,
  useDashboardSync,
  VISIBILITY_HIDDEN_MIN_MS,
} from './useDashboardSync'

/*
 * Story 2.12: the precision verification and the catch-up after a long gap share the sync's job table and its
 * following rules (Story 2.11). These tests drive the same hook through `POST /api/dashboard/verification`.
 */

const TOKEN = 'sync-csrf-token'
const LATE_AT = '2026-09-25T00:00:01Z'
const CATCH_UP_FROM = '2026-08-20T01:00:00Z'

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

type Answer = () => Promise<Response>

/** Answers the dashboard reads in order; after the queue, the last answer again. */
function dashboards(...queue: Answer[]): void {
  let last: Answer | undefined
  stubDashboard(() => {
    const next = queue.shift() ?? last
    if (next === undefined) throw new Error('an unexpected dashboard read')
    last = next
    return next()
  })
}

/** Answers the sync admissions in order (the entry sync of the first ready answer, a manual press). */
function syncs(...queue: Answer[]): void {
  stubDashboardSync(() => {
    const next = queue.shift()
    if (next === undefined) throw new Error('an unexpected sync admission')
    return next()
  })
}

/** Answers the verification admissions in order, recording each request's options. */
function verifications(...queue: Answer[]): (RequestInit | undefined)[] {
  const inits: (RequestInit | undefined)[] = []
  stubDashboardVerification((init) => {
    inits.push(init)
    const next = queue.shift()
    if (next === undefined) throw new Error('an unexpected verification admission')
    return next()
  })
  return inits
}

interface Blocks {
  readonly sync?: Record<string, unknown>
  readonly verification?: Record<string, unknown>
  readonly change?: Record<string, unknown>
}

function ready(blocks: Blocks = {}, version = 1, nextAction = 'NONE'): Answer {
  return () =>
    Promise.resolve(
      json(
        dashboardFixture(
          planResultFixture({
            pointerVersion: version,
            calculationId: `calculation-${String(version)}`,
          }),
          nextAction,
          blocks.sync ?? syncFixture(),
          blocks.verification ?? verificationFixture(),
          blocks.change ?? changeFixture(),
        ),
      ),
    )
}

function admitted(
  outcome: string,
  job: Record<string, unknown> | null,
  retry: number | null = null,
): Answer {
  return () =>
    Promise.resolve(
      json({ outcome, job, retryAfterSeconds: retry }, outcome === 'ACCEPTED' ? 202 : 200),
    )
}

function refused(status: number, code: string): Answer {
  return () => Promise.resolve(json({ code, message: 'x', correlationId: 'c' }, status))
}

function reads(): number {
  return getObservedAllowedRequests().filter((entry) => entry === `GET ${DASHBOARD_PATH}`).length
}

function verificationPosts(): number {
  return getObservedAllowedRequests().filter(
    (entry) => entry === `POST ${DASHBOARD_VERIFICATION_PATH}`,
  ).length
}

function render(token: string | null = TOKEN) {
  return renderHook(
    ({ csrf }: { csrf: string | null }) => {
      const dashboard = useDashboard(true)
      const sync = useDashboardSync(dashboard, csrf)
      return { dashboard, sync }
    },
    { initialProps: { csrf: token } },
  )
}

type Rendered = ReturnType<typeof render>['result']

function announce(result: Rendered): string {
  const { dashboard, sync } = result.current
  return dashboardAnnouncement(dashboard, sync.phase, sync.purpose)
}

afterEach(() => {
  vi.useRealTimers()
  Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => 'visible' })
})

async function flush(): Promise<void> {
  for (let turn = 0; turn < 8; turn += 1) {
    await act(async () => {
      await vi.advanceTimersByTimeAsync(0)
    })
  }
}

async function advance(ms: number): Promise<void> {
  await act(async () => {
    await vi.advanceTimersByTimeAsync(ms)
  })
  await flush()
}

async function steps(count: number, ms: number): Promise<void> {
  for (let step = 0; step < count; step += 1) await advance(ms)
}

const running = verificationJobFixture({ status: 'RUNNING', stage: 'COLLECTION' })
const finished = (fields: Record<string, unknown>) =>
  verificationJobFixture({ finishedAt: '2026-09-27T01:10:40Z', ...fields })
const inProgress: Blocks = {
  sync: syncFixture({ availability: 'IN_PROGRESS', latest: running }),
  verification: verificationFixture({ availability: 'IN_PROGRESS', latest: running }),
}
const corrected = changeFixture({
  kind: 'CORRECTED',
  purpose: 'VERIFY',
  reason: 'GAP_SUSPECTED',
  lateChangeCount: 1,
  firstAffectedAt: LATE_AT,
  targets: [{ kind: 'ANOMALY', start: '2026-09-25T00:00:00Z', end: '2026-09-26T00:00:01Z' }],
})

describe('parsing the verification block, the version change and the admission', () => {
  it('accepts the whole block, and folds explicit and absent nulls alike', () => {
    expect(parseVerificationStatus(verificationFixture())).toEqual(AVAILABLE_VERIFICATION)
    expect(
      parseVerificationStatus({
        availability: 'LONG_GAP',
        retryAfterSeconds: 12,
        catchUpFrom: CATCH_UP_FROM,
      }),
    ).toMatchObject({ availability: 'LONG_GAP', retryAfterSeconds: 12, catchUpFrom: CATCH_UP_FROM })
    expect(
      parseVerificationStatus(verificationFixture({ availability: 'NOT_USEFUL', preview: null })),
    ).toMatchObject({ availability: 'NOT_USEFUL', preview: null })
  })

  it('keeps unknown codes and a latest job with details it cannot read as none', () => {
    const job = finished({
      status: 'NEEDS_REVIEW',
      failureCode: 'NEW_CODE',
      reason: 'NEW_REASON',
      purpose: 'SOMETHING_NEW',
      targets: [{ kind: 'NEW_KIND', start: 'yesterday', end: 'today' }],
      counts: { new: -1 },
    })
    expect(parseVerificationStatus(verificationFixture({ latest: job }))).toMatchObject({
      availability: 'AVAILABLE',
      latest: {
        purpose: 'SOMETHING_NEW',
        reason: 'NEW_REASON',
        failureCode: 'NEW_CODE',
        // Unreadable, which is not the same as none.
        targets: null,
        reused: [],
        counts: null,
        holdsResult: false,
      },
    })
    // A preview that is not whole is shown as none; the availability stays.
    expect(
      parseVerificationStatus(verificationFixture({ preview: { rangeStart: 'x' } })),
    ).toMatchObject({ availability: 'AVAILABLE', preview: null })
  })

  it.each([
    ['no block at all', undefined],
    ['a block that is not an object', 'AVAILABLE'],
    ['an availability it does not know', verificationFixture({ availability: 'MAYBE' })],
    ['a cool-down without seconds', verificationFixture({ availability: 'COOLDOWN' })],
    ['zero seconds', verificationFixture({ availability: 'COOLDOWN', retryAfterSeconds: 0 })],
    ['a catch-up start in another format', verificationFixture({ catchUpFrom: '2026-08-20' })],
    ['a reason that is not a string', verificationFixture({ reason: 3 })],
    ['a latest job that is not whole', verificationFixture({ latest: { jobId: 'x' } })],
  ])(
    '%s folds to a verification that is not offered, never dropping the bundle',
    (_name, block) => {
      expect(parseVerificationStatus(block)).toMatchObject({
        availability: 'UNAVAILABLE',
        reason: null,
        retryAfterSeconds: null,
        preview: null,
        catchUpFrom: null,
      })
      const parsed = parseDashboard({ ...dashboardFixture(), verification: block })
      expect(parsed).toMatchObject({
        state: 'READY',
        verification: { availability: 'UNAVAILABLE' },
      })
    },
  )

  it('ignores a field where it means nothing, and a long gap without its start blocks only the catch-up', () => {
    expect(
      parseVerificationStatus(
        verificationFixture({
          retryAfterSeconds: 10,
          catchUpFrom: CATCH_UP_FROM,
          reason: 'RESTRICTED',
        }),
      ),
    ).toEqual(AVAILABLE_VERIFICATION)
    expect(
      parseVerificationStatus(verificationFixture({ availability: 'LONG_GAP', preview: null })),
    ).toMatchObject({ availability: 'LONG_GAP', catchUpFrom: null })
  })

  it('C3: a catch-up start is kept whenever the gap may be long, and a suggested reason only while a verification can be asked for', () => {
    for (const availability of ['NOT_USEFUL', 'UNAVAILABLE', 'IN_PROGRESS', 'LONG_GAP']) {
      const latest =
        availability === 'IN_PROGRESS' ? verificationJobFixture({ status: 'RUNNING' }) : null
      const parsed = parseVerificationStatus(
        verificationFixture({
          availability,
          reason: availability === 'UNAVAILABLE' ? 'RESTRICTED' : null,
          suggestedReason: 'GAP_SUSPECTED',
          catchUpFrom: CATCH_UP_FROM,
          latest,
        }),
      )
      expect(parsed).toMatchObject({
        availability,
        catchUpFrom: CATCH_UP_FROM,
        suggestedReason: null,
      })
      expect(inLongGap(parsed)).toBe(true)
    }
    for (const availability of ['AVAILABLE', 'COOLDOWN']) {
      const parsed = parseVerificationStatus(
        verificationFixture({
          availability,
          retryAfterSeconds: availability === 'COOLDOWN' ? 5 : null,
          suggestedReason: 'GAP_SUSPECTED',
          catchUpFrom: CATCH_UP_FROM,
        }),
      )
      expect(parsed).toMatchObject({ catchUpFrom: null, suggestedReason: 'GAP_SUSPECTED' })
      expect(inLongGap(parsed)).toBe(false)
    }
  })

  it('P10: a job holds the shown result only when the server says so in a boolean, and only once it ended in review', () => {
    const review = finished({ status: 'NEEDS_REVIEW', failureCode: 'SOURCE_EVENT_MISSING' })
    const holding = (job: Record<string, unknown>) =>
      heldBy(
        parseVerificationStatus(verificationFixture({ availability: 'NOT_USEFUL', latest: job })),
      )
    expect(holding({ ...review, holdsResult: true })).toMatchObject({ holdsResult: true })
    expect(holding(review)).toBeNull()
    expect(holding({ ...review, holdsResult: 'true' })).toBeNull()
    expect(holding({ ...review, holdsResult: 1 })).toBeNull()
    expect(holding(finished({ status: 'SUCCEEDED', holdsResult: true }))).toBeNull()
    expect(holding(verificationJobFixture({ status: 'RUNNING', holdsResult: true }))).toBeNull()
    // A malformed flag never makes the block unreadable.
    expect(
      parseVerificationStatus(verificationFixture({ latest: { ...review, holdsResult: 'x' } })),
    ).toMatchObject({ availability: 'AVAILABLE', latest: { holdsResult: false } })
  })

  it('an unknown availability keeps the latest job it could read', () => {
    const job = finished({ status: 'SUCCEEDED' })
    expect(
      parseVerificationStatus(verificationFixture({ availability: 'MAYBE', latest: job })),
    ).toMatchObject({ ...UNKNOWN_VERIFICATION, latest: { status: 'SUCCEEDED' } })
  })

  it('reads the version change, and shows nothing for one it cannot vouch for', () => {
    expect(parseVersionChange(changeFixture())).toEqual(NO_CHANGE)
    expect(parseVersionChange(corrected)).toMatchObject({
      kind: 'CORRECTED',
      lateChangeCount: 1,
      firstAffectedAt: LATE_AT,
      targets: [{ kind: 'ANOMALY' }],
    })
    for (const broken of [
      undefined,
      changeFixture({ kind: 'SOMETHING_NEW' }),
      changeFixture({ kind: 'CORRECTED', lateChangeCount: 1, firstAffectedAt: 'soon' }),
      changeFixture({ kind: 'VERIFIED_NO_CHANGE', targets: [{ kind: 'NEW' }] }),
      changeFixture({ lateChangeCount: -1 }),
    ]) {
      expect(parseVersionChange(broken)).toEqual(NO_CHANGE)
      expect(parseDashboard({ ...dashboardFixture(), change: broken })).toMatchObject({
        state: 'READY',
        change: { kind: 'NONE' },
      })
    }
  })

  it('P4: a version change that contradicts itself shows no marker', () => {
    for (const broken of [
      changeFixture({ kind: 'VERIFIED_NO_CHANGE', lateChangeCount: 1 }),
      changeFixture({ kind: 'VERIFIED_NO_CHANGE', firstAffectedAt: LATE_AT }),
      changeFixture({ kind: 'NONE', lateChangeCount: 2 }),
    ]) {
      expect(parseVersionChange(broken)).toEqual(NO_CHANGE)
    }
    // An impact that starts after the shown basis time.
    const late = changeFixture({
      kind: 'CORRECTED',
      lateChangeCount: 1,
      firstAffectedAt: '2026-09-27T01:00:01Z',
    })
    expect(parseVersionChange(late, planResultFixture().basisAt as string)).toEqual(NO_CHANGE)
    expect(
      parseDashboard(
        dashboardFixture(planResultFixture(), 'NONE', syncFixture(), verificationFixture(), late),
      ),
    ).toMatchObject({ state: 'READY', change: { kind: 'NONE' } })
    // At the basis time itself it is still one.
    expect(
      parseVersionChange(
        { ...late, firstAffectedAt: planResultFixture().basisAt },
        planResultFixture().basisAt as string,
      ),
    ).toMatchObject({ kind: 'CORRECTED' })
  })

  it('a correction without a late cash flow is still a correction, said without a count', () => {
    expect(
      parseVersionChange(changeFixture({ kind: 'CORRECTED', lateChangeCount: 0 })),
    ).toMatchObject({ kind: 'CORRECTED', lateChangeCount: 0 })
    expect(planVerificationContent.change.corrected(0)).not.toMatch(/\d/)
    expect(planVerificationContent.change.correctedSentence(0)).not.toMatch(/\d/)
  })

  it('accepts the VERIFY next action and a job without a purpose as a sync', () => {
    expect(parseDashboard(dashboardFixture(planResultFixture(), 'VERIFY'))).not.toBeNull()
    const old = Object.fromEntries(
      Object.entries(syncJobFixture()).filter(([key]) => key !== 'purpose' && key !== 'reason'),
    )
    expect(parseSyncJob(old)).toMatchObject({ purpose: 'SYNC', reason: null })
    expect(parseSyncJob(syncJobFixture({ purpose: 3 }))).toBeNull()
    expect(parseSyncJob(syncJobFixture({ reason: false }))).toBeNull()
  })

  it('reads the three verification admission outcomes, and nothing only a sync can answer', () => {
    const job = verificationJobFixture()
    expect(
      parseVerificationAdmission({ outcome: 'ACCEPTED', job, retryAfterSeconds: null }),
    ).toMatchObject({ outcome: 'ACCEPTED', job: { purpose: 'VERIFY', reason: 'USER_REQUEST' } })
    expect(parseVerificationAdmission({ outcome: 'JOINED', job })).not.toBeNull()
    expect(
      parseVerificationAdmission({ outcome: 'COOLDOWN', job: null, retryAfterSeconds: 9 }),
    ).toMatchObject({ retryAfterSeconds: 9 })
    expect(parseVerificationAdmission({ outcome: 'NOT_DUE', job: null })).toBeNull()
    expect(parseVerificationAdmission({ outcome: 'LONG_GAP', job: null })).toBeNull()
    expect(parseVerificationAdmission({ outcome: 'ACCEPTED', job: null })).toBeNull()
  })
})

describe('useDashboardSync: a precision verification', () => {
  it('sends the shown reference once, shows it running at once, follows it quietly, and swaps in the corrected bundle with focus on the heading and one sentence each way', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'Date'] })
    dashboards(
      ready(),
      ready(inProgress),
      ready(inProgress),
      ready(
        {
          sync: syncFixture({
            availability: 'COOLDOWN',
            retryAfterSeconds: 25,
            latest: finished({ status: 'SUCCEEDED' }),
          }),
          verification: verificationFixture({
            availability: 'COOLDOWN',
            retryAfterSeconds: 25,
            latest: finished({ status: 'SUCCEEDED' }),
          }),
          change: corrected,
        },
        2,
      ),
    )
    syncs(admitted('NOT_DUE', null, 240))
    const inits = verifications(admitted('ACCEPTED', verificationJobFixture()))
    const { result } = render()
    await flush()
    expect(result.current.sync.canVerify).toBe(true)
    const said: string[] = []
    const record = (): void => {
      const text = announce(result)
      if (said.at(-1) !== text) said.push(text)
    }

    act(() => {
      result.current.sync.requestVerification('PRECISION')
      result.current.sync.requestVerification('PRECISION')
    })
    // Before any answer: running as a verification, and neither button asks.
    expect(result.current.sync.running).toBe(true)
    expect(result.current.sync.purpose).toBe('VERIFY')
    expect(result.current.sync.canVerify).toBe(false)
    expect(result.current.sync.canRequest).toBe(false)
    record()
    await flush()
    expect(verificationPosts()).toBe(1)
    const [init] = inits
    expect(init?.method).toBe('POST')
    expect(init?.cache).toBe('no-store')
    expect(init?.credentials).toBe('same-origin')
    expect(init?.body).toBe(
      JSON.stringify({
        kind: 'PRECISION',
        planId: 'plan-1',
        pointerVersion: 1,
        calculationId: 'calculation-1',
      }),
    )
    const headers = init?.headers as Record<string, string>
    expect(headers[CSRF_HEADER]).toBe(TOKEN)
    expect(headers['Content-Type']).toBe('application/json')
    expect(headers['Idempotency-Key']).toMatch(/^[0-9a-f-]{36}$/)

    // Two polls in progress: quiet, the old bundle kept, the sentence unchanged.
    for (const expected of [2, 3]) {
      await advance(SYNC_POLL_INTERVAL_MS)
      expect(reads()).toBe(expected)
      expect(result.current.dashboard.loading).toBe(false)
      expect(result.current.dashboard.view).toMatchObject({ result: { pointerVersion: 1 } })
      record()
    }
    await advance(SYNC_POLL_INTERVAL_MS)
    expect(result.current.dashboard.view).toMatchObject({
      kind: 'READY',
      result: { pointerVersion: 2 },
      change: { kind: 'CORRECTED', lateChangeCount: 1 },
    })
    expect(result.current.sync.phase).toBe('succeeded')
    expect(result.current.sync.purpose).toBe('VERIFY')
    expect(result.current.sync.focus).toMatchObject({ target: 'heading' })
    record()
    const basis = formatSeoulTime(
      planResultFixture().basisAt as string,
      planResultContent.timeUnavailable,
    )
    expect(said).toEqual([
      planVerificationContent.status.running,
      planVerificationContent.status.succeeded(
        planVerificationContent.change.correctedSentence(1),
        null,
        basis,
      ),
    ])
    expect(said.join(' ')).not.toContain(planResultContent.status.reloading)
    // The cool-down counts down from the server's relative seconds, then the button asks again.
    expect(result.current.sync.verificationCooldownSeconds).toBe(25)
    expect(result.current.sync.canVerify).toBe(false)
    await steps(25, 1_000)
    expect(result.current.sync.verificationCooldownSeconds).toBeNull()
    expect(result.current.sync.canVerify).toBe(true)
    await steps(10, SYNC_POLL_INTERVAL_MS)
    expect(reads()).toBe(4)
    expect(verificationPosts()).toBe(1)
    expect(getObservedStorageEntries()).toEqual([])
  })

  it('a verification with no change says so in its one closing sentence', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'Date'] })
    dashboards(
      ready(),
      ready(
        {
          verification: verificationFixture({ latest: finished({ status: 'SUCCEEDED' }) }),
          change: changeFixture({
            kind: 'VERIFIED_NO_CHANGE',
            purpose: 'VERIFY',
            reason: 'USER_REQUEST',
            verifiedAt: '2026-09-27T01:10:40Z',
          }),
        },
        2,
      ),
    )
    syncs(admitted('NOT_DUE', null, 240))
    verifications(admitted('ACCEPTED', verificationJobFixture()))
    const { result } = render()
    await flush()
    act(() => {
      result.current.sync.requestVerification('PRECISION')
    })
    await flush()
    await advance(SYNC_POLL_INTERVAL_MS)
    expect(result.current.sync.phase).toBe('succeeded')
    expect(announce(result)).toContain(planVerificationContent.change.verifiedSentence)
    expect(announce(result)).not.toMatch(/전체 이력/)
  })

  it('a verification that ends in review moves focus to the verification summary, and the same basis is then not useful', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'Date'] })
    const review = finished({
      status: 'NEEDS_REVIEW',
      failureCode: 'SOURCE_EVENT_MISSING',
      stage: 'COLLECTION',
      counts: { new: 0, same: 2, missing: 1, superseded: 0, conflict: 0 },
    })
    dashboards(
      ready(),
      ready({
        sync: syncFixture({ latest: review }),
        verification: verificationFixture({ availability: 'NOT_USEFUL', latest: review }),
      }),
    )
    syncs(admitted('NOT_DUE', null, 240))
    verifications(admitted('ACCEPTED', verificationJobFixture()))
    const { result } = render()
    await flush()
    act(() => {
      result.current.sync.requestVerification('PRECISION')
    })
    await flush()
    await advance(SYNC_POLL_INTERVAL_MS)
    expect(result.current.sync.phase).toBe('review')
    expect(result.current.sync.purpose).toBe('VERIFY')
    expect(result.current.sync.focus).toMatchObject({ target: 'verification' })
    expect(result.current.sync.canVerify).toBe(false)
    expect(result.current.dashboard.view).toMatchObject({ result: { pointerVersion: 1 } })
    expect(announce(result)).toBe(planVerificationContent.status.review)
    // Nothing is retried by itself.
    await steps(10, SYNC_POLL_INTERVAL_MS)
    expect(verificationPosts()).toBe(1)
  })

  // What the one quiet read after a refusal shows: the server's state that the refusal reported.
  const unavailable: Blocks = {
    verification: verificationFixture({ availability: 'UNAVAILABLE', reason: 'RESTRICTED' }),
  }
  it.each([
    ['VERIFICATION_NOT_USEFUL', 'notUseful', 'verification', 1, {}],
    // The stale summary lasts until its own quiet read answers; then the replacement is said once.
    ['REFERENCE_STALE', 'refreshed', 'verification', 1, {}],
    ['JOB_IN_PROGRESS', 'busy', 'verification', 1, {}],
    ['VERIFICATION_UNAVAILABLE', 'unavailable', 'verification', 1, unavailable],
    ['LONG_GAP', 'longGap', 'summary', 1, {}],
    // A catch-up's refusal met by a precision verification: a general refusal, never an error.
    ['NOT_LONG_GAP', 'refused', 'verification', 1, {}],
    ['IDEMPOTENCY_CONFLICT', 'error', 'verification', 0, {}],
    ['SOMETHING_NEW', 'error', 'verification', 0, {}],
  ] as const)(
    'a 409 %s is said once as %s, moves focus to the %s and reads the dashboard %i more time(s), quietly',
    async (code, phase, target, more, after: Blocks) => {
      vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'Date'] })
      dashboards(ready(), ready(after))
      syncs(admitted('NOT_DUE', null, 240))
      verifications(refused(409, code))
      const { result } = render()
      await flush()
      act(() => {
        result.current.sync.requestVerification('PRECISION')
      })
      await flush()
      expect(result.current.sync.phase).toBe(phase)
      expect(result.current.sync.purpose).toBe('VERIFY')
      expect(result.current.sync.focus).toMatchObject({ target })
      expect(reads()).toBe(1 + more)
      expect(result.current.dashboard.loading).toBe(false)
      expect(result.current.sync.running).toBe(false)
      await steps(5, SYNC_POLL_INTERVAL_MS)
      expect(verificationPosts()).toBe(1)
    },
  )

  it('P15: a stale reference is said until its own quiet read answers, then the new bundle is said once', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'Date'] })
    let release: (() => void) | undefined
    const held = new Promise<void>((resolve) => {
      release = resolve
    })
    dashboards(ready(), () => held.then(() => ready({}, 2)()))
    syncs(admitted('NOT_DUE', null, 240))
    verifications(refused(409, 'REFERENCE_STALE'))
    const { result } = render()
    await flush()
    act(() => {
      result.current.sync.requestVerification('PRECISION')
    })
    await flush()
    // The quiet read is in flight: the refusal is said, and nothing reads as busy.
    expect(result.current.sync.phase).toBe('stale')
    expect(announce(result)).toBe(planVerificationContent.status.stale)
    expect(result.current.dashboard.loading).toBe(false)
    release?.()
    await flush()
    expect(result.current.dashboard.view).toMatchObject({ result: { pointerVersion: 2 } })
    expect(result.current.sync.phase).toBe('refreshed')
    const basis = formatSeoulTime(
      planResultFixture().basisAt as string,
      planResultContent.timeUnavailable,
    )
    expect(announce(result)).toBe(planVerificationContent.status.refreshed(null, basis))
    // It asks nothing by itself, and a read the user asks for lets the sentence go.
    expect(verificationPosts()).toBe(1)
    act(() => {
      result.current.dashboard.reload()
    })
    await flush()
    expect(result.current.sync.phase).toBe('idle')
  })

  it('P15: a quiet read that no longer reports the cool-down ends the cool-down outcome', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'Date'] })
    dashboards(ready())
    syncs(admitted('NOT_DUE', null, 240))
    verifications(admitted('COOLDOWN', null, 30))
    const { result } = render()
    await flush()
    act(() => {
      result.current.sync.requestVerification('PRECISION')
    })
    await flush()
    expect(result.current.sync.phase).toBe('cooldown')
    expect(result.current.sync.verificationCooldownSeconds).toBe(30)
    act(() => {
      result.current.dashboard.refresh()
    })
    await flush()
    expect(result.current.sync.verificationCooldownSeconds).toBeNull()
    expect(result.current.sync.phase).toBe('idle')
    expect(result.current.sync.canVerify).toBe(true)
  })

  it.each(['PRECISION', 'CATCH_UP'] as const)(
    'P23: a 409 NO_ACTIVE_PLAN for %s says nothing, moves no focus and reads the dashboard once, out loud',
    async (kind) => {
      vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'Date'] })
      const longGap: Blocks = {
        sync: syncFixture({ availability: 'LONG_GAP' }),
        verification: verificationFixture({
          availability: 'LONG_GAP',
          preview: null,
          catchUpFrom: CATCH_UP_FROM,
        }),
      }
      const first = kind === 'CATCH_UP' ? ready(longGap, 1, 'CHOOSE_CONTINUATION') : ready()
      let release: (() => void) | undefined
      const held = new Promise<void>((resolve) => {
        release = resolve
      })
      dashboards(first, () =>
        held.then(() => json({ state: 'NO_PLAN', nextAction: 'CREATE_PLAN' })),
      )
      if (kind === 'PRECISION') syncs(admitted('NOT_DUE', null, 240))
      verifications(refused(409, 'NO_ACTIVE_PLAN'))
      const { result } = render()
      await flush()
      act(() => {
        result.current.sync.requestVerification(kind)
      })
      await flush()
      // One read, out loud: the dashboard says it is reading again.
      expect(reads()).toBe(2)
      expect(result.current.dashboard.loading).toBe(true)
      expect(result.current.sync.phase).toBe('idle')
      expect(result.current.sync.running).toBe(false)
      expect(result.current.sync.focus).toBeNull()
      release?.()
      await flush()
      expect(reads()).toBe(2)
      expect(result.current.sync.phase).toBe('idle')
      expect(result.current.sync.focus).toBeNull()
      expect(result.current.dashboard.view.kind).toBe('NO_PLAN')
    },
  )

  it('P14: an automatic sync admission in flight blocks a verification until it answers', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'Date'] })
    let answer: ((response: Response) => void) | undefined
    dashboards(ready())
    stubDashboardSync(
      () =>
        new Promise<Response>((resolve) => {
          answer = resolve
        }),
    )
    verifications(admitted('ACCEPTED', verificationJobFixture()))
    const { result } = render()
    await flush()
    // The entry sync is in flight: nothing runs on screen, but a verification cannot be asked for.
    expect(result.current.sync.running).toBe(false)
    expect(result.current.sync.phase).toBe('idle')
    expect(result.current.sync.canVerify).toBe(false)
    act(() => {
      result.current.sync.requestVerification('PRECISION')
    })
    await flush()
    expect(verificationPosts()).toBe(0)
    expect(result.current.sync.phase).toBe('idle')
    answer?.(json({ outcome: 'NOT_DUE', job: null, retryAfterSeconds: null }))
    await flush()
    expect(result.current.sync.canVerify).toBe(true)
    act(() => {
      result.current.sync.requestVerification('PRECISION')
    })
    await flush()
    expect(verificationPosts()).toBe(1)
  })

  it.each([
    ['a server error', () => Promise.resolve(json({ code: 'INTERNAL_ERROR' }, 500))],
    ['a malformed answer', () => Promise.resolve(json({ outcome: 'ACCEPTED' }, 202))],
    ['a network failure', () => Promise.reject(new Error('network'))],
  ])('%s is said as an error, with focus, and asks nothing more', async (_name, answer) => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'Date'] })
    dashboards(ready())
    syncs(admitted('NOT_DUE', null, 240))
    verifications(answer)
    const { result } = render()
    await flush()
    act(() => {
      result.current.sync.requestVerification('PRECISION')
    })
    await flush()
    expect(result.current.sync.phase).toBe('error')
    expect(result.current.sync.focus).toMatchObject({ target: 'verification' })
    expect(announce(result)).toBe(planVerificationContent.status.error)
    expect(result.current.sync.canVerify).toBe(true)
  })

  it('a cool-down answer counts down on the verification clock and blocks only the verification', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'Date'] })
    dashboards(ready())
    syncs(admitted('NOT_DUE', null, 240))
    verifications(admitted('COOLDOWN', null, 3))
    const { result } = render()
    await flush()
    act(() => {
      result.current.sync.requestVerification('PRECISION')
    })
    await flush()
    expect(result.current.sync.phase).toBe('cooldown')
    expect(result.current.sync.purpose).toBe('VERIFY')
    expect(result.current.sync.focus).toMatchObject({ target: 'verification' })
    expect(result.current.sync.verificationCooldownSeconds).toBe(3)
    expect(result.current.sync.cooldownSeconds).toBeNull()
    expect(result.current.sync.canVerify).toBe(false)
    expect(result.current.sync.canRequest).toBe(true)
    await steps(3, 1_000)
    expect(result.current.sync.verificationCooldownSeconds).toBeNull()
    expect(result.current.sync.phase).toBe('idle')
    expect(result.current.sync.canVerify).toBe(true)
  })

  it('a refusal (403) reads the dashboard again out loud and says the request was not handled', async () => {
    dashboards(ready(), ready())
    syncs(admitted('NOT_DUE', null, 240))
    verifications(refused(403, 'FORBIDDEN'))
    const { result } = render()
    await waitFor(() => {
      expect(result.current.sync.canVerify).toBe(true)
    })
    act(() => {
      result.current.sync.requestVerification('PRECISION')
    })
    await waitFor(() => {
      expect(reads()).toBe(2)
    })
    await waitFor(() => {
      expect(result.current.dashboard.loading).toBe(false)
    })
    expect(result.current.sync.phase).toBe('error')
    expect(result.current.sync.focus).toMatchObject({ target: 'verification' })
  })

  it('a sync that joins a verification in progress follows it as a verification', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'Date'] })
    dashboards(
      ready(),
      ready(inProgress),
      ready(
        {
          sync: syncFixture({ latest: finished({ status: 'SUCCEEDED' }) }),
          verification: verificationFixture({ latest: finished({ status: 'SUCCEEDED' }) }),
        },
        2,
      ),
    )
    syncs(admitted('NOT_DUE', null, 240), admitted('JOINED', running))
    const { result } = render()
    await flush()
    act(() => {
      result.current.sync.request()
    })
    expect(result.current.sync.purpose).toBe('SYNC')
    await flush()
    expect(result.current.sync.running).toBe(true)
    expect(result.current.sync.purpose).toBe('VERIFY')
    expect(announce(result)).toBe(planVerificationContent.status.running)
    await advance(SYNC_POLL_INTERVAL_MS)
    await advance(SYNC_POLL_INTERVAL_MS)
    expect(result.current.sync.phase).toBe('succeeded')
    expect(result.current.sync.purpose).toBe('VERIFY')
    expect(result.current.sync.focus).toMatchObject({ target: 'heading' })
  })

  it('follows a verification found in progress only in the verification block, without focus', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'Date'] })
    const earlierSync = syncJobFixture({
      jobId: '0a0b0c0d-0e0f-4a1b-8c2d-3e4f5a6b7c8d',
      status: 'SUCCEEDED',
      admittedAt: '2026-09-27T01:00:00Z',
      finishedAt: '2026-09-27T01:00:04Z',
    })
    dashboards(
      ready({
        sync: syncFixture({ availability: 'COOLDOWN', retryAfterSeconds: 20, latest: earlierSync }),
        verification: verificationFixture({ availability: 'IN_PROGRESS', latest: running }),
      }),
      ready({
        sync: syncFixture({ latest: earlierSync }),
        verification: verificationFixture({
          latest: finished({ status: 'FAILED', failureCode: 'PROVIDER_UNAVAILABLE' }),
        }),
      }),
    )
    const { result } = render()
    await flush()
    expect(result.current.sync.running).toBe(true)
    expect(result.current.sync.purpose).toBe('VERIFY')
    expect(result.current.sync.followingElsewhere).toBe(true)
    await advance(SYNC_POLL_INTERVAL_MS)
    expect(result.current.sync.phase).toBe('failed')
    expect(result.current.sync.purpose).toBe('VERIFY')
    expect(result.current.sync.focus).toBeNull()
    expect(verificationPosts()).toBe(0)
  })

  it('never asks for a verification by itself: not on entry, not after a long absence', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'Date'] })
    dashboards(ready())
    syncs(admitted('NOT_DUE', null, 240), admitted('NOT_DUE', null, 240))
    const { result } = render()
    await flush()
    let visibility: DocumentVisibilityState = 'hidden'
    Object.defineProperty(document, 'visibilityState', {
      configurable: true,
      get: () => visibility,
    })
    act(() => {
      document.dispatchEvent(new Event('visibilitychange'))
    })
    await advance(VISIBILITY_HIDDEN_MIN_MS)
    visibility = 'visible'
    act(() => {
      document.dispatchEvent(new Event('visibilitychange'))
    })
    await flush()
    act(() => {
      result.current.dashboard.reload()
    })
    await flush()
    expect(verificationPosts()).toBe(0)
    expect(getObservedRequests().filter((entry) => entry.includes('verification'))).toEqual([])
  })

  it('without the session token a press asks nothing and says so', async () => {
    dashboards(ready())
    const { result } = render(null)
    await waitFor(() => {
      expect(result.current.sync.canVerify).toBe(true)
    })
    act(() => {
      result.current.sync.requestVerification('PRECISION')
    })
    expect(result.current.sync.phase).toBe('error')
    expect(result.current.sync.purpose).toBe('VERIFY')
    expect(result.current.sync.focus).toMatchObject({ target: 'verification' })
    expect(verificationPosts()).toBe(0)
    expect(getObservedRequests()).toEqual([])
  })

  it('a press is ignored when the verification cannot be asked for', async () => {
    dashboards(ready({ verification: verificationFixture({ availability: 'NOT_USEFUL' }) }))
    syncs(admitted('NOT_DUE', null, 240))
    const { result } = render()
    await waitFor(() => {
      expect(result.current.dashboard.view.kind).toBe('READY')
    })
    expect(result.current.sync.canVerify).toBe(false)
    act(() => {
      result.current.sync.requestVerification('PRECISION')
      result.current.sync.requestVerification('CATCH_UP')
    })
    expect(result.current.sync.phase).toBe('idle')
    expect(verificationPosts()).toBe(0)
  })
})

describe('useDashboardSync: the catch-up after a long gap', () => {
  const longGap: Blocks = {
    sync: syncFixture({ availability: 'LONG_GAP' }),
    verification: verificationFixture({
      availability: 'LONG_GAP',
      preview: null,
      catchUpFrom: CATCH_UP_FROM,
    }),
  }
  const catchUpJob = (fields: Record<string, unknown> = {}) =>
    verificationJobFixture({
      purpose: 'CATCH_UP',
      reason: 'LONG_GAP_CONTINUATION',
      targets: [{ kind: 'UNCONFIRMED', start: CATCH_UP_FROM, end: '2026-09-27T01:10:05Z' }],
      ...fields,
    })

  it('sends CATCH_UP with the shown reference, runs as a catch-up, and an ending in review moves focus to the sync control summary', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'Date'] })
    const review = catchUpJob({
      status: 'NEEDS_REVIEW',
      failureCode: 'SOURCE_REQUIRES_REVIEW',
      stage: 'COLLECTION',
      finishedAt: '2026-09-27T01:12:00Z',
    })
    dashboards(
      ready(longGap, 1, 'CHOOSE_CONTINUATION'),
      ready(
        {
          sync: syncFixture({
            availability: 'IN_PROGRESS',
            latest: catchUpJob({ status: 'RUNNING' }),
          }),
          verification: verificationFixture({
            availability: 'IN_PROGRESS',
            latest: catchUpJob({ status: 'RUNNING' }),
          }),
        },
        1,
        'WAIT_FOR_SYNC',
      ),
      ready(
        {
          sync: syncFixture({ availability: 'LONG_GAP', latest: review }),
          verification: verificationFixture({
            availability: 'LONG_GAP',
            preview: null,
            catchUpFrom: CATCH_UP_FROM,
            latest: review,
          }),
        },
        1,
        'CHOOSE_CONTINUATION',
      ),
    )
    const inits = verifications(admitted('ACCEPTED', catchUpJob()))
    const { result } = render()
    await flush()
    // No entry sync after a long gap, and no verification: only the catch-up can be asked for.
    expect(result.current.sync.canRequest).toBe(false)
    expect(result.current.sync.canVerify).toBe(false)
    expect(result.current.sync.canCatchUp).toBe(true)
    act(() => {
      result.current.sync.requestVerification('CATCH_UP')
    })
    expect(result.current.sync.running).toBe(true)
    expect(result.current.sync.purpose).toBe('CATCH_UP')
    expect(announce(result)).toBe(planVerificationContent.catchUp.status.running)
    await flush()
    expect(JSON.parse(inits[0]?.body as string)).toEqual({
      kind: 'CATCH_UP',
      planId: 'plan-1',
      pointerVersion: 1,
      calculationId: 'calculation-1',
    })
    await advance(SYNC_POLL_INTERVAL_MS)
    expect(result.current.sync.running).toBe(true)
    await advance(SYNC_POLL_INTERVAL_MS)
    expect(result.current.sync.phase).toBe('review')
    expect(result.current.sync.purpose).toBe('CATCH_UP')
    expect(result.current.sync.focus).toMatchObject({ target: 'summary' })
    expect(announce(result)).toBe(planVerificationContent.catchUp.status.review)
    expect(result.current.sync.canCatchUp).toBe(true)
  })

  it('a catch-up that is not needed any more says so, reads once quietly and is not retried', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'Date'] })
    dashboards(ready(longGap, 1, 'CHOOSE_CONTINUATION'), ready())
    syncs(admitted('NOT_DUE', null, 240))
    verifications(refused(409, 'NOT_LONG_GAP'))
    const { result } = render()
    await flush()
    act(() => {
      result.current.sync.requestVerification('CATCH_UP')
    })
    await flush()
    expect(result.current.sync.phase).toBe('notLongGap')
    expect(result.current.sync.purpose).toBe('CATCH_UP')
    expect(result.current.sync.focus).toMatchObject({ target: 'summary' })
    expect(reads()).toBe(2)
    expect(result.current.sync.canCatchUp).toBe(false)
    expect(announce(result)).toBe(planVerificationContent.catchUp.status.notLongGap)
  })

  it.each([
    // C3: a long gap whose basis a review holds makes the catch-up not useful either — its own sentence.
    ['VERIFICATION_NOT_USEFUL', 'notUseful', planVerificationContent.catchUp.status.notUseful],
    // A precision verification's refusal met by a catch-up: a general refusal, never an error.
    ['LONG_GAP', 'refused', planVerificationContent.catchUp.status.refused],
  ] as const)(
    'a catch-up refused with %s is said as %s in its own words, with focus on the sync control, and read once quietly',
    async (code, phase, sentence) => {
      vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'Date'] })
      dashboards(ready(longGap, 1, 'CHOOSE_CONTINUATION'))
      verifications(refused(409, code))
      const { result } = render()
      await flush()
      act(() => {
        result.current.sync.requestVerification('CATCH_UP')
      })
      await flush()
      expect(result.current.sync.phase).toBe(phase)
      expect(result.current.sync.purpose).toBe('CATCH_UP')
      expect(result.current.sync.focus).toMatchObject({ target: 'summary' })
      expect(announce(result)).toBe(sentence)
      expect(reads()).toBe(2)
      expect(result.current.dashboard.loading).toBe(false)
      await steps(5, SYNC_POLL_INTERVAL_MS)
      expect(verificationPosts()).toBe(1)
    },
  )

  it('the catch-up cool-down comes with the long gap, and blocks the button until it ends', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'Date'] })
    dashboards(
      ready(
        {
          ...longGap,
          verification: verificationFixture({
            availability: 'LONG_GAP',
            preview: null,
            catchUpFrom: CATCH_UP_FROM,
            retryAfterSeconds: 2,
          }),
        },
        1,
        'CHOOSE_CONTINUATION',
      ),
    )
    const { result } = render()
    await flush()
    expect(result.current.sync.verificationCooldownSeconds).toBe(2)
    expect(result.current.sync.canCatchUp).toBe(false)
    await steps(2, 1_000)
    expect(result.current.sync.canCatchUp).toBe(true)
  })

  it('the sentences of a catch-up that ended with a new bundle, and of a job over a read that did not answer', () => {
    const view: DashboardView = {
      kind: 'READY',
      nextAction: 'NONE',
      result: planResultFixture() as never,
      sync: { availability: 'AVAILABLE', reason: null, retryAfterSeconds: null, latest: null },
      verification: AVAILABLE_VERIFICATION,
      change: NO_CHANGE,
    }
    const dashboard: Dashboard = { view, loading: false, reload: vi.fn(), refresh: vi.fn() }
    const basis = formatSeoulTime(
      planResultFixture().basisAt as string,
      planResultContent.timeUnavailable,
    )
    expect(dashboardAnnouncement(dashboard, 'succeeded', 'CATCH_UP')).toBe(
      planVerificationContent.catchUp.status.succeeded(null, basis),
    )
    expect(dashboardAnnouncement(dashboard, 'longGap', 'VERIFY')).toBe(
      planSyncContent.status.longGap,
    )
    const lastGood: Dashboard = { ...dashboard, view: { ...view, kind: 'lastGood' } }
    expect(dashboardAnnouncement(lastGood, 'running', 'VERIFY')).toBe(
      planVerificationContent.status.running,
    )
    expect(dashboardAnnouncement(lastGood, 'stalled', 'CATCH_UP')).toBe(
      planVerificationContent.catchUp.status.stalled,
    )
    expect(dashboardAnnouncement(lastGood, 'failed', 'VERIFY')).toBe(
      planResultContent.status.lastGood,
    )
  })
})
