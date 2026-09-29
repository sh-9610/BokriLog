import { act, renderHook, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'

import { CSRF_HEADER } from '../content/auth'
import { DASHBOARD_PATH, planResultContent } from '../content/planResult'
import { DASHBOARD_SYNC_PATH, planSyncContent } from '../content/planSync'
import {
  dashboardFixture,
  NO_PLAN_FIXTURE,
  planResultFixture,
  syncFixture,
  syncJobFixture,
} from '../test/planResultFixture'
import {
  getObservedAllowedRequests,
  getObservedRequests,
  getObservedStorageEntries,
  stubDashboard,
  stubDashboardSync,
} from '../test/setup'
import {
  dashboardAnnouncement,
  inProgress,
  parseDashboard,
  parseSyncJob,
  parseSyncStatus,
  UNKNOWN_SYNC,
  useDashboard,
} from './useDashboard'
import {
  parseAdmission,
  SYNC_POLL_INTERVAL_MS,
  SYNC_POLL_LIMIT_MS,
  useDashboardSync,
  VISIBILITY_HIDDEN_MIN_MS,
} from './useDashboardSync'

const TOKEN = 'sync-csrf-token'
const JOB_ID = '1f2e3d4c-5b6a-4978-8a9b-0c1d2e3f4a5b'

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

/** Answers the sync admissions in order, recording each request's options. */
function admissions(...queue: Answer[]): (RequestInit | undefined)[] {
  const inits: (RequestInit | undefined)[] = []
  stubDashboardSync((init) => {
    inits.push(init)
    const next = queue.shift()
    if (next === undefined) throw new Error('an unexpected sync admission')
    return next()
  })
  return inits
}

function ready(sync: Record<string, unknown> = syncFixture(), version = 1): Answer {
  return () =>
    Promise.resolve(
      json(
        dashboardFixture(
          planResultFixture({
            pointerVersion: version,
            calculationId: `calculation-${String(version)}`,
          }),
          'NONE',
          sync,
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

function posts(): number {
  return getObservedAllowedRequests().filter((entry) => entry === `POST ${DASHBOARD_SYNC_PATH}`)
    .length
}

function triggerOf(init: RequestInit | undefined): string {
  return (JSON.parse(init?.body as string) as { trigger: string }).trigger
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

let visibility: DocumentVisibilityState = 'visible'

function setVisibility(state: DocumentVisibilityState): void {
  visibility = state
  Object.defineProperty(document, 'visibilityState', {
    configurable: true,
    get: () => visibility,
  })
  act(() => {
    document.dispatchEvent(new Event('visibilitychange'))
  })
}

afterEach(() => {
  vi.useRealTimers()
  visibility = 'visible'
  Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => 'visible' })
})

/** Lets every answer already given settle, without moving the clock. */
async function flush(): Promise<void> {
  for (let turn = 0; turn < 8; turn += 1) {
    await act(async () => {
      await vi.advanceTimersByTimeAsync(0)
    })
  }
}

/**
 * Moves the clock and lets what it started settle. React applies what a timer did when the act scope ends,
 * so a chain of timers (one poll after each answer, one second of cool-down after another) moves one step per
 * call: longer spans go through `steps`.
 */
async function advance(ms: number): Promise<void> {
  await act(async () => {
    await vi.advanceTimersByTimeAsync(ms)
  })
  await flush()
}

async function steps(count: number, ms: number): Promise<void> {
  for (let step = 0; step < count; step += 1) await advance(ms)
}

/** Presses 동기화 once the first answer is in, and lets the admission answer land (fake timers). */
async function pressed(result: { current: { sync: { request: () => void } } }): Promise<void> {
  await flush()
  act(() => {
    result.current.sync.request()
  })
  await flush()
}

/** The tab is hidden for 5 minutes and shown again (fake timers). */
async function returnAfterLongAbsence(): Promise<void> {
  setVisibility('hidden')
  await advance(VISIBILITY_HIDDEN_MIN_MS)
  setVisibility('visible')
  await flush()
}

describe('parsing the sync block and the admission', () => {
  it('accepts the whole block and folds explicit and absent nulls alike', () => {
    expect(parseSyncStatus(syncFixture())).toEqual({
      availability: 'AVAILABLE',
      reason: null,
      retryAfterSeconds: null,
      latest: null,
    })
    expect(parseSyncStatus({ availability: 'AVAILABLE' })).toEqual(parseSyncStatus(syncFixture()))
    expect(
      parseSyncStatus(
        syncFixture({
          availability: 'IN_PROGRESS',
          latest: syncJobFixture({ status: 'RUNNING', stage: 'COLLECTION' }),
        }),
      ),
    ).toMatchObject({ availability: 'IN_PROGRESS', latest: { status: 'RUNNING' } })
  })

  it('P14: keeps unknown codes as they came — the screen folds them to safe copy', () => {
    const unknown = syncJobFixture({
      trigger: 'CRON',
      status: 'PAUSED',
      stage: 'NEW_STAGE',
      failureCode: 'NEW_CODE',
      finishedAt: '2026-09-27T01:10:05Z',
    })
    expect(parseSyncStatus(syncFixture({ latest: unknown }))).toMatchObject({
      availability: 'AVAILABLE',
      latest: { trigger: 'CRON', status: 'PAUSED', stage: 'NEW_STAGE', failureCode: 'NEW_CODE' },
    })
    // A status it does not know is an ending, whatever its end time.
    const open = parseSyncJob(syncJobFixture({ status: 'PAUSED' }))
    expect(open).not.toBeNull()
    expect(open === null ? true : inProgress(open)).toBe(false)
  })

  it('P14: an availability it does not know is unavailable for a reason it does not know, keeping the job', () => {
    const job = syncJobFixture({
      status: 'FAILED',
      failureCode: 'SYNC_FAILED',
      finishedAt: '2026-09-27T01:10:05Z',
    })
    expect(parseSyncStatus(syncFixture({ availability: 'MAYBE', latest: job }))).toMatchObject({
      ...UNKNOWN_SYNC,
      latest: { jobId: JOB_ID, status: 'FAILED' },
    })
  })

  it.each([
    ['no block at all', undefined],
    ['a block that is not an object', 'AVAILABLE'],
    ['a reason while available', syncFixture({ reason: 'RESTRICTED' })],
    ['unavailable without a reason', syncFixture({ availability: 'UNAVAILABLE' })],
    ['a reason that is not a string', syncFixture({ availability: 'UNAVAILABLE', reason: 7 })],
    ['a cool-down without seconds', syncFixture({ availability: 'COOLDOWN' })],
    ['seconds while available', syncFixture({ retryAfterSeconds: 10 })],
    ['zero seconds', syncFixture({ availability: 'COOLDOWN', retryAfterSeconds: 0 })],
    ['fractional seconds', syncFixture({ availability: 'COOLDOWN', retryAfterSeconds: 1.5 })],
    ['in progress without a job', syncFixture({ availability: 'IN_PROGRESS' })],
    [
      'in progress with an ended job',
      syncFixture({
        availability: 'IN_PROGRESS',
        latest: syncJobFixture({ status: 'SUCCEEDED', finishedAt: '2026-09-27T01:10:05Z' }),
      }),
    ],
    [
      'a running job with an end time',
      syncFixture({ latest: syncJobFixture({ finishedAt: '2026-09-27T01:10:05Z' }) }),
    ],
    [
      'an ended job without an end time',
      syncFixture({ latest: syncJobFixture({ status: 'FAILED', failureCode: 'SYNC_FAILED' }) }),
    ],
    [
      'a failure without a code',
      syncFixture({
        latest: syncJobFixture({ status: 'BLOCKED', finishedAt: '2026-09-27T01:10:05Z' }),
      }),
    ],
    [
      'a success with a code',
      syncFixture({
        latest: syncJobFixture({
          status: 'SUCCEEDED',
          failureCode: 'SYNC_FAILED',
          finishedAt: '2026-09-27T01:10:05Z',
        }),
      }),
    ],
    [
      'an admission time in another format',
      syncFixture({ latest: syncJobFixture({ admittedAt: '2026-09-27 01:10:00' }) }),
    ],
    ['a job without an id', syncFixture({ latest: syncJobFixture({ jobId: '' }) })],
    ['a trigger that is not a string', syncFixture({ latest: syncJobFixture({ trigger: 1 }) })],
  ])(
    'P14: %s is dropped to unavailable for an unknown reason, never a reason to drop the bundle',
    (_name, block) => {
      expect(parseSyncStatus(block)).toMatchObject({
        availability: 'UNAVAILABLE',
        reason: null,
        retryAfterSeconds: null,
      })
      const answer = dashboardFixture(planResultFixture(), 'NONE', {})
      const parsed = parseDashboard({ ...answer, sync: block })
      expect(parsed).toMatchObject({
        state: 'READY',
        result: { planId: 'plan-1' },
        sync: { availability: 'UNAVAILABLE', reason: null },
      })
    },
  )

  it('accepts the two new next actions, and ignores a sync block on a state without a bundle', () => {
    for (const next of ['SYNC', 'CHOOSE_CONTINUATION', 'WAIT_FOR_SYNC', 'NONE']) {
      expect(parseDashboard(dashboardFixture(planResultFixture(), next))).not.toBeNull()
    }
    expect(parseDashboard({ ...NO_PLAN_FIXTURE, sync: null })).toEqual({
      state: 'NO_PLAN',
      nextAction: 'CREATE_PLAN',
    })
    expect(parseDashboard({ ...NO_PLAN_FIXTURE, sync: syncFixture() })).toEqual({
      state: 'NO_PLAN',
      nextAction: 'CREATE_PLAN',
    })
  })

  it('reads the five admission outcomes, each with what it must carry', () => {
    const job = syncJobFixture()
    expect(parseAdmission({ outcome: 'ACCEPTED', job, retryAfterSeconds: null })).toMatchObject({
      outcome: 'ACCEPTED',
      job: { jobId: JOB_ID, status: 'QUEUED' },
    })
    expect(parseAdmission({ outcome: 'JOINED', job, retryAfterSeconds: null })).not.toBeNull()
    expect(parseAdmission({ outcome: 'COOLDOWN', job, retryAfterSeconds: 12 })).toMatchObject({
      retryAfterSeconds: 12,
    })
    expect(parseAdmission({ outcome: 'NOT_DUE', job: null, retryAfterSeconds: 240 })).not.toBeNull()
    expect(
      parseAdmission({ outcome: 'LONG_GAP', job: null, retryAfterSeconds: null }),
    ).not.toBeNull()
    expect(parseAdmission({ outcome: 'ACCEPTED', job: null, retryAfterSeconds: null })).toBeNull()
    expect(parseAdmission({ outcome: 'COOLDOWN', job, retryAfterSeconds: null })).toBeNull()
    expect(parseAdmission({ outcome: 'QUEUED', job, retryAfterSeconds: null })).toBeNull()
    expect(parseAdmission({ outcome: 'JOINED', job: { jobId: 'x' } })).toBeNull()
    expect(parseAdmission(null)).toBeNull()
  })
})

describe('useDashboardSync: triggers', () => {
  it('sends ENTRY once per mount on the first ready answer, with the session token, a key and no-store', async () => {
    dashboards(ready())
    const inits = admissions(admitted('NOT_DUE', null, 240))
    const { result } = render()

    await waitFor(() => {
      expect(posts()).toBe(1)
    })
    const [init] = inits
    expect(triggerOf(init)).toBe('ENTRY')
    expect(init).toMatchObject({ method: 'POST', credentials: 'same-origin', cache: 'no-store' })
    const headers = init?.headers as Record<string, string>
    expect(headers[CSRF_HEADER]).toBe(TOKEN)
    expect(headers['Content-Type']).toBe('application/json')
    expect(headers['Idempotency-Key']).toMatch(/^[A-Za-z0-9._:-]{16,128}$/)
    // NOT_DUE: nothing was admitted, and nothing is said or shown.
    await waitFor(() => {
      expect(result.current.sync.phase).toBe('idle')
    })
    expect(result.current.sync.running).toBe(false)

    // Reading again (the user's reload) is not a new mount: no second ENTRY.
    act(() => {
      result.current.dashboard.reload()
    })
    await waitFor(() => {
      expect(reads()).toBe(2)
    })
    await new Promise((settle) => setTimeout(settle, 30))
    expect(posts()).toBe(1)
    expect(getObservedStorageEntries()).toEqual([])
  })

  it.each([
    ['cooling down', syncFixture({ availability: 'COOLDOWN', retryAfterSeconds: 20 })],
    ['after a long gap', syncFixture({ availability: 'LONG_GAP' })],
    ['unavailable', syncFixture({ availability: 'UNAVAILABLE', reason: 'RESTRICTED' })],
  ])('does not send an automatic trigger while %s', async (_name, block) => {
    dashboards(ready(block))
    render()
    await waitFor(() => {
      expect(reads()).toBe(1)
    })
    await new Promise((settle) => setTimeout(settle, 30))
    expect(posts()).toBe(0)
    // The global guard saw no hidden request either.
    expect(getObservedRequests()).toEqual([])
  })

  it('sends nothing without the session token', async () => {
    dashboards(ready())
    render(null)
    await waitFor(() => {
      expect(reads()).toBe(1)
    })
    await new Promise((settle) => setTimeout(settle, 30))
    expect(posts()).toBe(0)
    expect(getObservedRequests()).toEqual([])
  })

  it('sends VISIBILITY only after the tab was hidden at least 5 minutes, on the next ready answer', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'Date'] })
    dashboards(ready())
    const inits = admissions(admitted('NOT_DUE', null, 240), admitted('NOT_DUE', null, 200))
    const { result } = render()
    await flush()
    expect(posts()).toBe(1)

    // Hidden for just under 5 minutes: nothing is due.
    setVisibility('hidden')
    await advance(VISIBILITY_HIDDEN_MIN_MS - 1_000)
    setVisibility('visible')
    act(() => {
      result.current.dashboard.reload()
    })
    await flush()
    expect(posts()).toBe(1)

    // Hidden for 5 minutes: the answer read after the tab came back sends VISIBILITY, once.
    setVisibility('hidden')
    await advance(VISIBILITY_HIDDEN_MIN_MS)
    setVisibility('visible')
    await flush()
    // Not before a new answer: the one shown was read before the tab was hidden.
    expect(posts()).toBe(1)
    act(() => {
      result.current.dashboard.reload()
    })
    await flush()
    expect(posts()).toBe(2)
    expect(triggerOf(inits[1])).toBe('VISIBILITY')
    act(() => {
      result.current.dashboard.reload()
    })
    await flush()
    expect(posts()).toBe(2)
  })

  it.each([
    ['cooling down', syncFixture({ availability: 'COOLDOWN', retryAfterSeconds: 20 })],
    ['after a long gap', syncFixture({ availability: 'LONG_GAP' })],
    ['unavailable', syncFixture({ availability: 'UNAVAILABLE', reason: 'RESTRICTED' })],
  ])(
    'P20/P17: VISIBILITY is not sent while %s, and the first answer after the return is its only chance',
    async (_name, block) => {
      vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'Date'] })
      let current = block
      stubDashboard(() =>
        Promise.resolve(json(dashboardFixture(planResultFixture(), 'NONE', current))),
      )
      admissions()
      const { result } = render()
      await flush()
      expect(posts()).toBe(0)
      await returnAfterLongAbsence()
      act(() => {
        result.current.dashboard.reload()
      })
      await flush()
      expect(posts()).toBe(0)
      // A much later answer that says a sync is available does not send it.
      current = syncFixture()
      act(() => {
        result.current.dashboard.reload()
      })
      await flush()
      expect(posts()).toBe(0)
      expect(getObservedRequests()).toEqual([])
    },
  )

  it('P17: without the session token the first answer after the return also ends the chance', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'Date'] })
    dashboards(ready())
    const inits = admissions(admitted('NOT_DUE', null, 240), admitted('NOT_DUE', null, 240))
    const { result, rerender } = render(null)
    await flush()
    await returnAfterLongAbsence()
    act(() => {
      result.current.dashboard.reload()
    })
    await flush()
    expect(posts()).toBe(0)
    // The token comes back later (a session re-check). This mount's entry sync goes out then, once; the
    // return's chance is gone, so no VISIBILITY follows it.
    rerender({ csrf: TOKEN })
    act(() => {
      result.current.dashboard.reload()
    })
    await flush()
    expect(inits.map(triggerOf)).toEqual(['ENTRY'])
  })

  it('P20: VISIBILITY is not sent while a manual job is followed, and the press keeps its focus', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'Date'] })
    const job = syncJobFixture({ status: 'RUNNING', stage: 'COLLECTION' })
    dashboards(
      ready(),
      ready(syncFixture({ availability: 'IN_PROGRESS', latest: job })),
      ready(
        syncFixture({
          latest: syncJobFixture({ status: 'SUCCEEDED', finishedAt: '2026-09-27T01:10:09Z' }),
        }),
        2,
      ),
    )
    admissions(admitted('NOT_DUE', null, 240), admitted('ACCEPTED', syncJobFixture()))
    const { result } = render()
    await pressed(result)
    await returnAfterLongAbsence()
    // Polling paused while hidden; the first answer after the return is the job still running.
    await advance(SYNC_POLL_INTERVAL_MS)
    expect(result.current.sync.running).toBe(true)
    expect(posts()).toBe(2)
    await advance(SYNC_POLL_INTERVAL_MS)
    expect(result.current.sync.phase).toBe('succeeded')
    expect(result.current.sync.focus).toMatchObject({ target: 'heading' })
    expect(posts()).toBe(2)
  })

  it('an ENTRY answer for the job a manual press is following does not take the press away', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'Date'] })
    let releaseEntry: (response: Response) => void = () => undefined
    dashboards(
      ready(),
      ready(
        syncFixture({
          latest: syncJobFixture({
            status: 'FAILED',
            failureCode: 'PROVIDER_UNAVAILABLE',
            finishedAt: '2026-09-27T01:10:09Z',
          }),
        }),
      ),
    )
    admissions(
      () =>
        new Promise<Response>((resolve) => {
          releaseEntry = resolve
        }),
      admitted('ACCEPTED', syncJobFixture()),
    )
    const { result } = render()
    await pressed(result)
    expect(result.current.sync.running).toBe(true)
    await act(async () => {
      releaseEntry(json({ outcome: 'JOINED', job: syncJobFixture(), retryAfterSeconds: null }))
      await Promise.resolve()
    })
    await flush()
    await advance(SYNC_POLL_INTERVAL_MS)
    expect(result.current.sync.phase).toBe('failed')
    expect(result.current.sync.focus).toMatchObject({ target: 'summary' })
  })
})

describe('useDashboardSync: a manual sync', () => {
  const running = syncJobFixture({ status: 'RUNNING', stage: 'COLLECTION' })
  const inProgress = syncFixture({ availability: 'IN_PROGRESS', latest: running })
  const ended = (fields: Record<string, unknown>) =>
    syncJobFixture({ finishedAt: '2026-09-27T01:10:09Z', ...fields })

  it('shows running at once, blocks a second press in the same tick, follows quietly every 2 seconds, and swaps in the new bundle once with focus on the heading', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'Date'] })
    dashboards(
      ready(),
      ready(inProgress),
      ready(inProgress),
      ready(
        syncFixture({
          availability: 'COOLDOWN',
          retryAfterSeconds: 21,
          latest: ended({ status: 'SUCCEEDED' }),
        }),
        2,
      ),
    )
    const inits = admissions(admitted('NOT_DUE', null, 240), admitted('ACCEPTED', syncJobFixture()))
    const { result } = render()
    await flush()
    expect(result.current.sync.canRequest).toBe(true)

    act(() => {
      result.current.sync.request()
      result.current.sync.request()
    })
    // Before any answer: running, and the button no longer asks.
    expect(result.current.sync.running).toBe(true)
    expect(result.current.sync.phase).toBe('running')
    expect(result.current.sync.canRequest).toBe(false)
    expect(dashboardAnnouncement(result.current.dashboard, result.current.sync.phase)).toContain(
      '동기화 중',
    )
    await flush()
    expect(posts()).toBe(2)
    expect(triggerOf(inits[1])).toBe('MANUAL')
    const announcement = dashboardAnnouncement(result.current.dashboard, result.current.sync.phase)

    // Two polls in progress: quiet (never loading), the old bundle kept, the sentence unchanged.
    for (const expected of [2, 3]) {
      await advance(SYNC_POLL_INTERVAL_MS - 1)
      expect(reads()).toBe(expected - 1)
      await advance(1)
      expect(reads()).toBe(expected)
      expect(result.current.dashboard.loading).toBe(false)
      expect(result.current.dashboard.view).toMatchObject({ result: { pointerVersion: 1 } })
      expect(dashboardAnnouncement(result.current.dashboard, result.current.sync.phase)).toBe(
        announcement,
      )
    }
    // The poll that shows the job ended: the new bundle in one piece, focus to the heading.
    await advance(SYNC_POLL_INTERVAL_MS)
    expect(reads()).toBe(4)
    expect(result.current.dashboard.view).toMatchObject({
      kind: 'READY',
      result: { pointerVersion: 2, calculationId: 'calculation-2' },
    })
    expect(result.current.sync.phase).toBe('succeeded')
    expect(result.current.sync.running).toBe(false)
    expect(result.current.sync.focus).toMatchObject({ target: 'heading' })
    expect(dashboardAnnouncement(result.current.dashboard, result.current.sync.phase)).toContain(
      '동기화를 마쳐 새 공식 결과를 표시합니다',
    )
    // The cool-down counts down from the server's relative seconds, then the button asks again.
    expect(result.current.sync.cooldownSeconds).toBe(21)
    expect(result.current.sync.canRequest).toBe(false)
    await advance(1_000)
    expect(result.current.sync.cooldownSeconds).toBe(20)
    await steps(20, 1_000)
    expect(result.current.sync.cooldownSeconds).toBeNull()
    expect(result.current.sync.canRequest).toBe(true)
    // Following has stopped: no further read, and nothing is retried.
    await steps(15, SYNC_POLL_INTERVAL_MS)
    expect(reads()).toBe(4)
    expect(posts()).toBe(2)
    expect(getObservedStorageEntries()).toEqual([])
  })

  it.each([
    ['FAILED', 'SOURCE_REJECTED', 'failed'],
    ['NEEDS_REVIEW', 'CLASSIFICATION_REQUIRES_REVIEW', 'review'],
    ['BLOCKED', 'POINTER_CONFLICT', 'blocked'],
  ] as const)(
    'a job that ends %s keeps the bundle, moves focus to the error summary and is not retried',
    async (status, code, phase) => {
      vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'Date'] })
      dashboards(ready(), ready(syncFixture({ latest: ended({ status, failureCode: code }) })))
      admissions(admitted('NOT_DUE', null, 240), admitted('ACCEPTED', syncJobFixture()))
      const { result } = render()
      await flush()
      act(() => {
        result.current.sync.request()
      })
      await flush()
      await advance(SYNC_POLL_INTERVAL_MS)
      expect(result.current.sync.phase).toBe(phase)
      expect(result.current.sync.focus).toMatchObject({ target: 'summary' })
      expect(result.current.dashboard.view).toMatchObject({ result: { pointerVersion: 1 } })
      expect(result.current.sync.canRequest).toBe(true)
      await steps(10, SYNC_POLL_INTERVAL_MS)
      expect(reads()).toBe(2)
      expect(posts()).toBe(2)
    },
  )

  it('an answer read before the job was admitted does not end the following', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'Date'] })
    const earlier = ended({
      jobId: '0a0b0c0d-0e0f-4a1b-8c2d-3e4f5a6b7c8d',
      status: 'FAILED',
      failureCode: 'PROVIDER_UNAVAILABLE',
      admittedAt: '2026-09-27T01:00:00Z',
      finishedAt: '2026-09-27T01:00:04Z',
    })
    dashboards(
      ready(syncFixture({ latest: earlier })),
      ready(syncFixture({ latest: earlier })),
      ready(syncFixture({ latest: ended({ status: 'SUCCEEDED' }) }), 2),
    )
    admissions(admitted('NOT_DUE', null, 240), admitted('ACCEPTED', syncJobFixture()))
    const { result } = render()
    await flush()
    act(() => {
      result.current.sync.request()
    })
    await flush()
    await advance(SYNC_POLL_INTERVAL_MS)
    expect(result.current.sync.running).toBe(true)
    await advance(SYNC_POLL_INTERVAL_MS)
    expect(result.current.sync.phase).toBe('succeeded')
  })

  it('P10: stops following after 3 minutes of visible time on its own timer, says so, moves focus to the summary and asks nothing more', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'Date'] })
    dashboards(ready(), ready(inProgress))
    admissions(admitted('NOT_DUE', null, 240), admitted('ACCEPTED', syncJobFixture()))
    const { result } = render()
    await pressed(result)
    // One read every 2 seconds for 3 minutes: the first read, then 89 polls.
    const polls = SYNC_POLL_LIMIT_MS / SYNC_POLL_INTERVAL_MS - 1
    await steps(polls, SYNC_POLL_INTERVAL_MS)
    expect(reads()).toBe(1 + polls)
    expect(result.current.sync.running).toBe(true)
    await advance(SYNC_POLL_INTERVAL_MS)
    expect(result.current.sync.phase).toBe('stalled')
    expect(result.current.sync.running).toBe(false)
    expect(result.current.sync.focus).toMatchObject({ target: 'summary' })
    expect(reads()).toBe(1 + polls)
    expect(dashboardAnnouncement(result.current.dashboard, result.current.sync.phase)).toBe(
      planSyncContent.status.stalled,
    )
    // The dashboard still says the job runs, but that job is not followed again by itself.
    await steps(10, SYNC_POLL_INTERVAL_MS)
    expect(reads()).toBe(1 + polls)
    expect(result.current.sync.running).toBe(false)
    expect(posts()).toBe(2)
  })

  it('P10: the budget ends even when no answer comes back at all, and ends as stalled over a failed read', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'Date'] })
    let answered = 0
    dashboards(ready(), () => {
      answered += 1
      // Every poll after the first hangs until its own time limit; the budget has a timer of its own.
      return answered === 1 ? ready(inProgress)() : new Promise<Response>(() => undefined)
    })
    admissions(admitted('NOT_DUE', null, 240), admitted('ACCEPTED', syncJobFixture()))
    const { result } = render()
    await pressed(result)
    await steps(SYNC_POLL_LIMIT_MS / 1_000 + 2, 1_000)
    expect(result.current.sync.phase).toBe('stalled')
    expect(result.current.sync.focus).toMatchObject({ target: 'summary' })
    // Whatever the view is (here the last good result of a read that did not answer), the sentence is `stalled`.
    expect(result.current.dashboard.view.kind).toBe('lastGood')
    expect(dashboardAnnouncement(result.current.dashboard, result.current.sync.phase)).toBe(
      planSyncContent.status.stalled,
    )
  })

  it('P10: hidden time does not count against the budget, and polling pauses while hidden', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'Date'] })
    dashboards(ready(), ready(inProgress))
    admissions(admitted('NOT_DUE', null, 240), admitted('ACCEPTED', syncJobFixture()))
    const { result } = render()
    await pressed(result)
    await advance(SYNC_POLL_INTERVAL_MS)
    expect(reads()).toBe(2)
    // 2 minutes visible, then 10 minutes hidden: nothing is read and the budget does not run.
    await steps(59, SYNC_POLL_INTERVAL_MS)
    setVisibility('hidden')
    const hiddenReads = reads()
    await advance(10 * 60_000)
    expect(reads()).toBe(hiddenReads)
    expect(result.current.sync.running).toBe(true)
    setVisibility('visible')
    // About a minute of the budget is left once the tab is visible again.
    await steps(25, SYNC_POLL_INTERVAL_MS)
    expect(result.current.sync.running).toBe(true)
    await steps(10, SYNC_POLL_INTERVAL_MS)
    expect(result.current.sync.phase).toBe('stalled')
  })

  it('P10: a read that does not answer does not end the following; polling goes on and a later answer ends it', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'Date'] })
    dashboards(
      ready(),
      () => Promise.reject(new Error('network')),
      () => Promise.resolve(json({}, 503)),
      ready(syncFixture({ latest: ended({ status: 'SUCCEEDED' }) }), 2),
    )
    admissions(admitted('NOT_DUE', null, 240), admitted('ACCEPTED', syncJobFixture()))
    const { result } = render()
    await pressed(result)
    const syncing = dashboardAnnouncement(result.current.dashboard, result.current.sync.phase)
    expect(syncing).toBe(planSyncContent.status.syncing)
    await advance(SYNC_POLL_INTERVAL_MS)
    expect(result.current.dashboard.view.kind).toBe('lastGood')
    expect(result.current.sync.running).toBe(true)
    // A failed poll does not change what the status region says while the job is followed.
    expect(dashboardAnnouncement(result.current.dashboard, result.current.sync.phase)).toBe(syncing)
    await advance(SYNC_POLL_INTERVAL_MS)
    expect(result.current.sync.running).toBe(true)
    await advance(SYNC_POLL_INTERVAL_MS)
    expect(reads()).toBe(4)
    expect(result.current.sync.phase).toBe('succeeded')
    expect(result.current.sync.focus).toMatchObject({ target: 'heading' })
  })

  it('P10: an admission answer that lands while the view is a failed read is still followed', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'Date'] })
    let release: (response: Response) => void = () => undefined
    dashboards(
      ready(),
      () => Promise.reject(new Error('network')),
      ready(syncFixture({ latest: ended({ status: 'SUCCEEDED' }) }), 2),
    )
    admissions(
      admitted('NOT_DUE', null, 240),
      () =>
        new Promise<Response>((resolve) => {
          release = resolve
        }),
    )
    const { result } = render()
    await flush()
    act(() => {
      result.current.sync.request()
    })
    // A reload fails before the admission answers.
    act(() => {
      result.current.dashboard.reload()
    })
    await flush()
    expect(result.current.dashboard.view.kind).toBe('lastGood')
    await act(async () => {
      release(json({ outcome: 'ACCEPTED', job: syncJobFixture(), retryAfterSeconds: null }, 202))
      await Promise.resolve()
    })
    await flush()
    expect(result.current.sync.running).toBe(true)
    await advance(SYNC_POLL_INTERVAL_MS)
    expect(result.current.sync.phase).toBe('succeeded')
    expect(result.current.sync.focus).toMatchObject({ target: 'heading' })
  })

  it('P10: a job given up by its budget is not followed again by itself, but an answer for it later starts a fresh budget', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'Date'] })
    dashboards(ready(), ready(inProgress))
    admissions(
      admitted('NOT_DUE', null, 240),
      admitted('ACCEPTED', syncJobFixture()),
      admitted('JOINED', running),
    )
    const { result } = render()
    await pressed(result)
    await steps(SYNC_POLL_LIMIT_MS / SYNC_POLL_INTERVAL_MS, SYNC_POLL_INTERVAL_MS)
    expect(result.current.sync.phase).toBe('stalled')
    // A reload that still shows the same job in progress does not follow it again.
    act(() => {
      result.current.dashboard.reload()
    })
    await flush()
    expect(result.current.sync.running).toBe(false)
    expect(result.current.sync.canRequest).toBe(false)
    // Later the server offers a sync, and a press is answered with that same job: a whole new budget.
    dashboards(ready())
    act(() => {
      result.current.dashboard.reload()
    })
    await flush()
    await pressed(result)
    expect(result.current.sync.running).toBe(true)
    await steps(60, SYNC_POLL_INTERVAL_MS)
    expect(result.current.sync.running).toBe(true)
    await steps(31, SYNC_POLL_INTERVAL_MS)
    expect(result.current.sync.phase).toBe('stalled')
  })

  it('a cool-down answer is said once, counts down, moves focus to the summary, and ends with the countdown (P11)', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'Date'] })
    dashboards(ready())
    admissions(admitted('NOT_DUE', null, 240), admitted('COOLDOWN', null, 3))
    const { result } = render()
    await pressed(result)
    expect(result.current.sync.phase).toBe('cooldown')
    expect(result.current.sync.focus).toMatchObject({ target: 'summary' })
    expect(result.current.sync.cooldownSeconds).toBe(3)
    expect(result.current.sync.canRequest).toBe(false)
    await steps(2, 1_000)
    expect(result.current.sync.cooldownSeconds).toBe(1)
    expect(result.current.sync.phase).toBe('cooldown')
    await advance(1_000)
    expect(result.current.sync.cooldownSeconds).toBeNull()
    expect(result.current.sync.canRequest).toBe(true)
    expect(result.current.sync.phase).toBe('idle')
    // Nothing was admitted: the dashboard was not read again.
    expect(reads()).toBe(1)
  })

  it('a long gap blocks the button and reads the dashboard once, quietly', async () => {
    dashboards(ready(), ready(syncFixture({ availability: 'LONG_GAP' })))
    admissions(admitted('NOT_DUE', null, 240), admitted('LONG_GAP', null))
    const { result } = render()
    await waitFor(() => {
      expect(result.current.sync.canRequest).toBe(true)
    })
    act(() => {
      result.current.sync.request()
    })
    await waitFor(() => {
      expect(result.current.sync.phase).toBe('longGap')
    })
    expect(result.current.sync.canRequest).toBe(false)
    expect(result.current.sync.focus).toMatchObject({ target: 'summary' })
    await waitFor(() => {
      expect(reads()).toBe(2)
    })
    expect(result.current.dashboard.loading).toBe(false)
  })

  it.each([
    ['a 409 IDEMPOTENCY_CONFLICT', refused(409, 'IDEMPOTENCY_CONFLICT')],
    ['a 422', refused(422, 'IDEMPOTENCY_KEY_REQUIRED')],
    ['a 500', refused(500, 'INTERNAL_ERROR')],
    ['a network failure', () => Promise.reject(new Error('network'))],
    ['a malformed answer', () => Promise.resolve(json({ outcome: 'ACCEPTED' }, 202))],
  ])('%s ends as error with focus on the summary, and is not retried', async (_name, answer) => {
    dashboards(ready())
    admissions(admitted('NOT_DUE', null, 240), answer)
    const { result } = render()
    await waitFor(() => {
      expect(result.current.sync.canRequest).toBe(true)
    })
    act(() => {
      result.current.sync.request()
    })
    await waitFor(() => {
      expect(result.current.sync.phase).toBe('error')
    })
    expect(result.current.sync.focus).toMatchObject({ target: 'summary' })
    expect(result.current.sync.running).toBe(false)
    await new Promise((settle) => setTimeout(settle, 30))
    expect(reads()).toBe(1)
    expect(posts()).toBe(2)
  })

  it('a 409 SYNC_UNAVAILABLE is said, reads once quietly for the reason, and ends when a read shows a sync is available (P11)', async () => {
    dashboards(
      ready(),
      ready(syncFixture({ availability: 'UNAVAILABLE', reason: 'RESTRICTED' })),
      ready(),
    )
    admissions(admitted('NOT_DUE', null, 240), refused(409, 'SYNC_UNAVAILABLE'))
    const { result } = render()
    await waitFor(() => {
      expect(result.current.sync.canRequest).toBe(true)
    })
    act(() => {
      result.current.sync.request()
    })
    await waitFor(() => {
      expect(reads()).toBe(2)
    })
    await waitFor(() => {
      expect(result.current.dashboard.view).toMatchObject({
        sync: { availability: 'UNAVAILABLE' },
      })
    })
    expect(result.current.sync.phase).toBe('unavailable')
    expect(result.current.sync.focus).toMatchObject({ target: 'summary' })
    expect(result.current.dashboard.loading).toBe(false)
    act(() => {
      result.current.dashboard.refresh()
    })
    await waitFor(() => {
      expect(result.current.dashboard.view).toMatchObject({ sync: { availability: 'AVAILABLE' } })
    })
    expect(result.current.sync.phase).toBe('idle')
  })

  it('P11: a read the user asks for ends whatever the last sync said', async () => {
    dashboards(ready(), ready())
    admissions(admitted('NOT_DUE', null, 240), refused(500, 'INTERNAL_ERROR'))
    const { result } = render()
    await waitFor(() => {
      expect(result.current.sync.canRequest).toBe(true)
    })
    act(() => {
      result.current.sync.request()
    })
    await waitFor(() => {
      expect(result.current.sync.phase).toBe('error')
    })
    act(() => {
      result.current.dashboard.reload()
    })
    expect(result.current.sync.phase).toBe('idle')
    await waitFor(() => {
      expect(result.current.dashboard.loading).toBe(false)
    })
    expect(result.current.sync.phase).toBe('idle')
    expect(dashboardAnnouncement(result.current.dashboard, result.current.sync.phase)).not.toBe(
      planSyncContent.status.error,
    )
  })

  it('P12: a manual 403 reads the dashboard again out loud and says the request was not handled', async () => {
    dashboards(ready(), ready())
    admissions(admitted('NOT_DUE', null, 240), refused(403, 'FORBIDDEN'))
    const { result } = render()
    await waitFor(() => {
      expect(result.current.sync.canRequest).toBe(true)
    })
    act(() => {
      result.current.sync.request()
    })
    await waitFor(() => {
      expect(reads()).toBe(2)
    })
    await waitFor(() => {
      expect(result.current.dashboard.loading).toBe(false)
    })
    // A CSRF or Origin refusal leaves the account readable: the refusal is not silent.
    expect(result.current.dashboard.view.kind).toBe('READY')
    expect(result.current.sync.phase).toBe('error')
    expect(result.current.sync.focus).toMatchObject({ target: 'summary' })
  })

  it.each([
    ['a refusal (403)', refused(403, 'FORBIDDEN'), 'error', 'summary'],
    ['a gone session (401)', refused(401, 'UNAUTHENTICATED'), 'error', 'summary'],
    ['no active plan (409)', refused(409, 'NO_ACTIVE_PLAN'), 'idle', null],
  ] as const)(
    'P12: %s lets the dashboard read again, out loud — and a refused account wins',
    async (_name, answer, phase, target) => {
      let release: (response: Response) => void = () => undefined
      dashboards(
        ready(),
        () =>
          new Promise<Response>((resolve) => {
            release = resolve
          }),
      )
      admissions(admitted('NOT_DUE', null, 240), answer)
      const { result } = render()
      await waitFor(() => {
        expect(result.current.sync.canRequest).toBe(true)
      })
      act(() => {
        result.current.sync.request()
      })
      await waitFor(() => {
        expect(result.current.dashboard.loading).toBe(true)
      })
      expect(result.current.sync.phase).toBe(phase)
      expect(result.current.sync.focus?.target ?? null).toBe(target)
      act(() => {
        release(json({ code: 'FORBIDDEN', message: 'x', correlationId: 'c' }, 403))
      })
      await waitFor(() => {
        expect(result.current.dashboard.view.kind).toBe('denied')
      })
      expect(result.current.sync.phase).toBe('idle')
      expect(result.current.sync.canRequest).toBe(false)
      expect(dashboardAnnouncement(result.current.dashboard, result.current.sync.phase)).toBe(
        planResultContent.status.denied,
      )
    },
  )

  it('P20: a manual press answered JOINED follows that job to its end, with focus', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'Date'] })
    dashboards(
      ready(),
      ready(inProgress),
      ready(
        syncFixture({
          latest: ended({ status: 'NEEDS_REVIEW', failureCode: 'SOURCE_REQUIRES_REVIEW' }),
        }),
      ),
    )
    admissions(admitted('NOT_DUE', null, 240), admitted('JOINED', running))
    const { result } = render()
    await pressed(result)
    expect(result.current.sync.running).toBe(true)
    expect(result.current.sync.followingElsewhere).toBe(false)
    await advance(SYNC_POLL_INTERVAL_MS)
    expect(result.current.sync.running).toBe(true)
    await advance(SYNC_POLL_INTERVAL_MS)
    expect(result.current.sync.phase).toBe('review')
    expect(result.current.sync.focus).toMatchObject({ target: 'summary' })
  })

  it('a press before any bundle does nothing; without the session token it asks nothing and says so', async () => {
    dashboards(ready())
    const { result } = render(null)
    act(() => {
      result.current.sync.request()
    })
    expect(result.current.sync.phase).toBe('idle')
    await waitFor(() => {
      expect(result.current.sync.canRequest).toBe(true)
    })
    act(() => {
      result.current.sync.request()
    })
    expect(result.current.sync.phase).toBe('error')
    expect(result.current.sync.focus).toMatchObject({ target: 'summary' })
    expect(posts()).toBe(0)
    expect(getObservedRequests()).toEqual([])
  })
})

describe('useDashboardSync: automatic syncs', () => {
  it('follows an admitted entry sync without moving focus, and says its end once', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'Date'] })
    dashboards(
      ready(),
      ready(
        syncFixture({
          latest: syncJobFixture({
            trigger: 'ENTRY',
            status: 'SUCCEEDED',
            finishedAt: '2026-09-27T01:10:09Z',
          }),
        }),
        2,
      ),
    )
    admissions(admitted('ACCEPTED', syncJobFixture({ trigger: 'ENTRY' })))
    const { result } = render()
    await flush()
    expect(result.current.sync.running).toBe(true)
    expect(result.current.sync.followingElsewhere).toBe(false)
    await advance(SYNC_POLL_INTERVAL_MS)
    expect(result.current.sync.phase).toBe('succeeded')
    expect(result.current.sync.focus).toBeNull()
    expect(result.current.dashboard.view).toMatchObject({ result: { pointerVersion: 2 } })
  })

  it('P12: an automatic trigger refused with 403 reads the dashboard again, silently', async () => {
    dashboards(ready(), ready())
    admissions(refused(403, 'FORBIDDEN'))
    const { result } = render()
    await waitFor(() => {
      expect(reads()).toBe(2)
    })
    await waitFor(() => {
      expect(result.current.dashboard.loading).toBe(false)
    })
    expect(result.current.sync.phase).toBe('idle')
    expect(result.current.sync.focus).toBeNull()
  })

  it('an automatic trigger that cannot be admitted says nothing', async () => {
    dashboards(ready())
    admissions(() => Promise.reject(new Error('network')))
    const { result } = render()
    await waitFor(() => {
      expect(posts()).toBe(1)
    })
    await new Promise((settle) => setTimeout(settle, 30))
    expect(result.current.sync.phase).toBe('idle')
    expect(result.current.sync.focus).toBeNull()
  })

  it('follows a job the server shows in progress without asking for one, and without focus', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'Date'] })
    const other = syncJobFixture({ trigger: 'VISIBILITY', status: 'RUNNING', stage: 'CONVERSION' })
    dashboards(
      ready(syncFixture({ availability: 'IN_PROGRESS', latest: other })),
      ready(
        syncFixture({
          latest: syncJobFixture({
            trigger: 'VISIBILITY',
            status: 'FAILED',
            failureCode: 'CONVERSION_REQUIRED',
            finishedAt: '2026-09-27T01:10:09Z',
          }),
        }),
      ),
    )
    const { result } = render()
    await flush()
    expect(result.current.sync.running).toBe(true)
    // P16: found in a read, not asked for here — the control says so beside the blocked button.
    expect(result.current.sync.followingElsewhere).toBe(true)
    expect(result.current.sync.canRequest).toBe(false)
    expect(posts()).toBe(0)
    await advance(SYNC_POLL_INTERVAL_MS)
    expect(result.current.sync.phase).toBe('failed')
    expect(result.current.sync.focus).toBeNull()
    await steps(10, SYNC_POLL_INTERVAL_MS)
    expect(reads()).toBe(2)
    expect(posts()).toBe(0)
  })
})
