import { act, renderHook, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'

import { CSRF_HEADER } from '../content/auth'
import { DASHBOARD_PATH } from '../content/planResult'
import {
  planSyncContent as copy,
  DASHBOARD_SYNC_PATH,
  REACTIVATION_HIDDEN_MS,
  SYNC_POLL_MS,
  SYNC_REQUEST_TIMEOUT_MS,
} from '../content/planSync'
import { dashboardFixture, NO_PLAN_FIXTURE, planResultFixture } from '../test/planResultFixture'
import {
  getObservedAllowedRequests,
  getObservedStorageEntries,
  stubDashboard,
  stubSyncAdmission,
  stubSyncState,
} from '../test/setup'
import { bundleOf, useDashboard } from './useDashboard'
import { manualEnabled, syncAnnouncement, useDashboardSync } from './useDashboardSync'

const CSRF = 'csrf-sync'

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

/** One attempt as `GET`/`POST /api/dashboard/sync` shows it. */
function job(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    jobId: 'job-1',
    trigger: 'MANUAL',
    status: 'QUEUED',
    stage: 'ADMITTED',
    mode: null,
    failureCode: null,
    retryable: false,
    createdAt: '2026-09-27T01:00:00Z',
    finishedAt: null,
    pointerVersion: null,
    ...overrides,
  }
}

const AVAILABLE = { available: true }
const RECENT = { outcome: 'RECENTLY_SYNCED', manual: AVAILABLE }

function admitted(overrides: Record<string, unknown> = {}): Response {
  return json({ outcome: 'ADMITTED', job: job(overrides), manual: AVAILABLE }, 202)
}

/** The admissions this test answered, with the trigger each one carried, in order. */
function admissions(...queue: ((init?: RequestInit) => Promise<Response>)[]): {
  triggers: string[]
  inits: RequestInit[]
} {
  const seen = { triggers: [] as string[], inits: [] as RequestInit[] }
  stubSyncAdmission((init) => {
    seen.inits.push(init ?? {})
    const body = JSON.parse(typeof init?.body === 'string' ? init.body : '{}') as {
      trigger: string
    }
    seen.triggers.push(body.trigger)
    const next = queue.shift()
    if (next === undefined) throw new Error('an unexpected sync admission')
    return next(init)
  })
  return seen
}

/** Answers the state reads in order; each entry is a response or a failure. */
function states(...queue: ((init?: RequestInit) => Promise<Response>)[]): void {
  stubSyncState((init) => {
    const next = queue.shift()
    if (next === undefined) throw new Error('an unexpected sync state read')
    return next(init)
  })
}

function stateReads(): number {
  return getObservedAllowedRequests().filter((entry) => entry === `GET ${DASHBOARD_SYNC_PATH}`)
    .length
}

function dashboardReads(): number {
  return getObservedAllowedRequests().filter((entry) => entry === `GET ${DASHBOARD_PATH}`).length
}

/** A response the test resolves later. */
function deferred(): { promise: Promise<Response>; resolve: (response: Response) => void } {
  let resolve: (response: Response) => void = () => undefined
  const promise = new Promise<Response>((settle) => {
    resolve = settle
  })
  return { promise, resolve }
}

/** The dashboard and its sync, as the two signed-in screens use them. */
function useHarness(csrf: string | null) {
  const dashboard = useDashboard(true)
  const sync = useDashboardSync(dashboard, csrf)
  return { dashboard, sync }
}

type Harness = ReturnType<typeof useHarness>

function pointerOf(result: { current: Harness }): number | null {
  return bundleOf(result.current.dashboard.view)?.result.pointerVersion ?? null
}

/** Lets fake timers run for `ms` and every promise they release settle. */
async function elapse(ms: number): Promise<void> {
  await act(async () => {
    await vi.advanceTimersByTimeAsync(ms)
  })
}

/** Lets the answers already given settle, without moving the clock. */
async function settle(): Promise<void> {
  await elapse(0)
  await elapse(0)
}

let visibility: DocumentVisibilityState = 'visible'

function setVisibility(state: DocumentVisibilityState): void {
  visibility = state
  document.dispatchEvent(new Event('visibilitychange'))
}

afterEach(() => {
  vi.useRealTimers()
  vi.restoreAllMocks()
  visibility = 'visible'
  // The own property one test defines; the prototype getter is back after it.
  Reflect.deleteProperty(document, 'visibilityState')
})

describe('useDashboardSync', () => {
  it('enters once per plan: one DASHBOARD_ENTRY admission on the first ready bundle, with the session token, none on a reload', async () => {
    stubDashboard(() => Promise.resolve(json(dashboardFixture())))
    const seen = admissions(() => Promise.resolve(json(RECENT)))
    const { result } = renderHook(() => useHarness(CSRF))

    await waitFor(() => {
      expect(result.current.sync.phase.kind).toBe('answered')
    })
    expect(seen.triggers).toEqual(['DASHBOARD_ENTRY'])
    const init = seen.inits[0]
    expect(init?.method).toBe('POST')
    expect(init?.credentials).toBe('same-origin')
    expect(new Headers(init?.headers).get(CSRF_HEADER)).toBe(CSRF)
    // An automatic trigger that started nothing says nothing and moves no focus.
    expect(syncAnnouncement(result.current.sync)).toBeNull()
    expect(result.current.sync.focus).toBeNull()
    expect(manualEnabled(result.current.sync)).toBe(true)

    act(() => {
      result.current.dashboard.reload()
    })
    await waitFor(() => {
      expect(dashboardReads()).toBe(2)
      expect(result.current.dashboard.loading).toBe(false)
    })
    await new Promise((done) => setTimeout(done, 50))
    expect(seen.triggers).toEqual(['DASHBOARD_ENTRY'])
    // No timer syncs on its own, and nothing was stored.
    expect(stateReads()).toBe(0)
    expect(getObservedStorageEntries()).toEqual([])
  })

  it('asks for nothing without a plan, or without the session token', async () => {
    stubDashboard(() => Promise.resolve(json(NO_PLAN_FIXTURE)))
    const seen = admissions()
    const { result: empty, unmount } = renderHook(() => useHarness(CSRF))
    await waitFor(() => {
      expect(empty.current.dashboard.view.kind).toBe('NO_PLAN')
    })
    act(() => {
      empty.current.sync.requestManual()
    })
    unmount()

    stubDashboard(() => Promise.resolve(json(dashboardFixture())))
    const { result: tokenless } = renderHook(() => useHarness(null))
    await waitFor(() => {
      expect(tokenless.current.dashboard.view.kind).toBe('READY')
    })
    act(() => {
      tokenless.current.sync.requestManual()
    })
    await new Promise((done) => setTimeout(done, 50))
    expect(seen.triggers).toEqual([])
    expect(tokenless.current.sync.phase.kind).toBe('unknown')
  })

  it('AC-7: the press says 동기화 중 at once, and a second press sends nothing while the request or the attempt is in progress', async () => {
    stubDashboard(() => Promise.resolve(json(dashboardFixture())))
    const pending = deferred()
    const seen = admissions(
      () => Promise.resolve(json(RECENT)),
      () => pending.promise,
    )
    const { result, unmount } = renderHook(() => useHarness(CSRF))
    await waitFor(() => {
      expect(result.current.sync.phase.kind).toBe('answered')
    })

    act(() => {
      result.current.sync.requestManual()
    })
    // Synchronously, before any answer: within the second the screen promises.
    expect(result.current.sync.phase.kind).toBe('submitting')
    expect(syncAnnouncement(result.current.sync)).toBe(copy.status.syncing)
    expect(manualEnabled(result.current.sync)).toBe(false)
    act(() => {
      result.current.sync.requestManual()
      result.current.sync.requestManual()
    })
    expect(seen.triggers).toEqual(['DASHBOARD_ENTRY', 'MANUAL'])

    await act(async () => {
      pending.resolve(admitted())
      await pending.promise
    })
    await waitFor(() => {
      expect(result.current.sync.phase.kind).toBe('syncing')
    })
    expect(syncAnnouncement(result.current.sync)).toBe(copy.status.syncing)
    act(() => {
      result.current.sync.requestManual()
    })
    expect(seen.triggers).toEqual(['DASHBOARD_ENTRY', 'MANUAL'])
    unmount()
  })

  it('AC-7: watches only while in progress, every two seconds, then swaps the whole bundle quietly and moves focus to the heading', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })
    let shown = dashboardFixture(planResultFixture({ pointerVersion: 1, calculationId: 'calc-1' }))
    stubDashboard(() => Promise.resolve(json(shown)))
    admissions(
      () => Promise.resolve(json(RECENT)),
      () => Promise.resolve(admitted()),
    )
    states(
      () =>
        Promise.resolve(
          json({ job: job({ status: 'RUNNING', stage: 'COLLECTION' }), manual: AVAILABLE }),
        ),
      () =>
        Promise.resolve(
          json({ job: job({ status: 'RUNNING', stage: 'CALCULATION' }), manual: AVAILABLE }),
        ),
      () =>
        Promise.resolve(
          json({
            job: job({
              status: 'PUBLISHED',
              stage: 'PUBLICATION',
              mode: 'NORMAL',
              finishedAt: '2026-09-27T01:00:05Z',
              pointerVersion: 2,
            }),
            manual: AVAILABLE,
          }),
        ),
    )
    const { result } = renderHook(() => useHarness(CSRF))
    await settle()
    expect(result.current.sync.phase.kind).toBe('answered')
    const readsBefore = dashboardReads()

    act(() => {
      result.current.sync.requestManual()
    })
    await settle()
    expect(result.current.sync.phase.kind).toBe('syncing')
    // The admission is followed by one quiet read, so the freshness axis can say 동기화 중 from the server.
    expect(dashboardReads()).toBe(readsBefore + 1)
    expect(result.current.dashboard.quiet).not.toBe(true)

    await elapse(SYNC_POLL_MS - 1)
    expect(stateReads()).toBe(0)
    await elapse(1)
    expect(stateReads()).toBe(1)
    expect(result.current.sync.phase.kind).toBe('syncing')
    await elapse(SYNC_POLL_MS)
    expect(stateReads()).toBe(2)
    // Values stay the last normal ones while the attempt runs.
    expect(pointerOf(result)).toBe(1)

    shown = dashboardFixture(planResultFixture({ pointerVersion: 2, calculationId: 'calc-2' }))
    await elapse(SYNC_POLL_MS)
    await settle()
    expect(stateReads()).toBe(3)
    expect(result.current.sync.phase).toMatchObject({ kind: 'ended', userInitiated: true })
    expect(syncAnnouncement(result.current.sync)).toBe(copy.status.published)
    expect(result.current.sync.focus).toEqual({ target: 'heading', token: 1 })
    // The new version replaced the whole bundle at once.
    expect(pointerOf(result)).toBe(2)
    expect(dashboardReads()).toBe(readsBefore + 2)

    // Nothing is read after the end: no timer syncs on its own.
    await elapse(SYNC_POLL_MS * 10)
    expect(stateReads()).toBe(3)
    expect(getObservedStorageEntries()).toEqual([])
  })

  it.each([
    {
      name: 'a review',
      end: { status: 'REVIEW_REQUIRED', failureCode: 'APPLIED_FLOW_CHANGED', retryable: false },
      sentence: copy.status.review,
    },
    {
      name: 'a retryable failure',
      end: { status: 'FAILED', failureCode: 'SOURCE_UNAVAILABLE', retryable: true },
      sentence: copy.status.failed(copy.failure('SOURCE_UNAVAILABLE').cause),
    },
    {
      name: 'a long gap found by the worker',
      end: { status: 'BLOCKED', failureCode: 'LONG_GAP_CHOICE_REQUIRED', retryable: false },
      sentence: copy.status.longGap,
    },
    {
      name: 'a refusal',
      end: { status: 'BLOCKED', failureCode: 'POINTER_CONFLICT', retryable: true },
      sentence: copy.status.blocked(copy.failure('POINTER_CONFLICT').cause),
    },
  ])(
    'AC-8·AC-9: an end without publication ($name) keeps the bundle, says so once and moves focus to the summary',
    async ({ end, sentence }) => {
      vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })
      stubDashboard(() => Promise.resolve(json(dashboardFixture())))
      admissions(
        () => Promise.resolve(json(RECENT)),
        () => Promise.resolve(admitted()),
      )
      states(() =>
        Promise.resolve(
          json({ job: job({ ...end, finishedAt: '2026-09-27T01:00:05Z' }), manual: AVAILABLE }),
        ),
      )
      const { result } = renderHook(() => useHarness(CSRF))
      await settle()
      act(() => {
        result.current.sync.requestManual()
      })
      await settle()
      await elapse(SYNC_POLL_MS)
      await settle()
      expect(result.current.sync.phase).toMatchObject({ kind: 'ended', userInitiated: true })
      expect(syncAnnouncement(result.current.sync)).toBe(sentence)
      expect(result.current.sync.focus).toEqual({ target: 'summary', token: 1 })
      expect(pointerOf(result)).toBe(1)
    },
  )

  it('an attempt an automatic trigger started ends without moving focus', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })
    stubDashboard(() => Promise.resolve(json(dashboardFixture())))
    admissions(() => Promise.resolve(admitted({ trigger: 'DASHBOARD_ENTRY' })))
    states(() =>
      Promise.resolve(
        json({
          job: job({
            trigger: 'DASHBOARD_ENTRY',
            status: 'FAILED',
            failureCode: 'CONVERSION_PENDING',
            retryable: true,
            finishedAt: '2026-09-27T01:00:05Z',
          }),
          manual: AVAILABLE,
        }),
      ),
    )
    const { result } = renderHook(() => useHarness(CSRF))
    await settle()
    expect(result.current.sync.phase).toMatchObject({ kind: 'syncing', userInitiated: false })
    await elapse(SYNC_POLL_MS)
    await settle()
    expect(result.current.sync.phase).toMatchObject({ kind: 'ended', userInitiated: false })
    expect(result.current.sync.focus).toBeNull()
    expect(manualEnabled(result.current.sync)).toBe(true)
  })

  it('three failed state reads in a row stop the reading — no retry loop', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })
    stubDashboard(() => Promise.resolve(json(dashboardFixture())))
    admissions(
      () => Promise.resolve(json(RECENT)),
      () => Promise.resolve(admitted()),
    )
    states(
      () => Promise.reject(new Error('network')),
      () => Promise.resolve(json({}, 500)),
      () => Promise.resolve(new Response('not json', { status: 200 })),
    )
    const { result } = renderHook(() => useHarness(CSRF))
    await settle()
    act(() => {
      result.current.sync.requestManual()
    })
    await settle()
    for (let read = 1; read <= 3; read += 1) {
      await elapse(SYNC_POLL_MS)
      await settle()
      expect(stateReads()).toBe(read)
    }
    expect(result.current.sync.phase.kind).toBe('stalled')
    expect(syncAnnouncement(result.current.sync)).toBe(copy.status.stalled)
    // The user's own press led here: the summary takes focus, as for a request that got no answer.
    expect(result.current.sync.focus).toMatchObject({ target: 'summary' })
    await elapse(SYNC_POLL_MS * 10)
    expect(stateReads()).toBe(3)
    // The server's last word on the button may no longer hold: unknown again, so the next press asks it.
    expect(result.current.sync.manual).toBeNull()
    expect(manualEnabled(result.current.sync)).toBe(true)
  })

  it.each([
    { status: 401, cause: 'signedOut', sentence: copy.status.signedOut },
    { status: 403, cause: 'refused', sentence: copy.status.denied },
  ])(
    "a $status while watching the user's own attempt stops everything",
    async ({ status, cause, sentence }) => {
      vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })
      stubDashboard(() => Promise.resolve(json(dashboardFixture())))
      admissions(
        () => Promise.resolve(json(RECENT)),
        () => Promise.resolve(admitted()),
      )
      states(() => Promise.resolve(json({ code: 'x', message: 'x', correlationId: 'c' }, status)))
      const { result } = renderHook(() => useHarness(CSRF))
      await settle()
      act(() => {
        result.current.sync.requestManual()
      })
      await settle()
      await elapse(SYNC_POLL_MS)
      await settle()
      expect(result.current.sync.phase).toMatchObject({ kind: 'denied', cause })
      expect(syncAnnouncement(result.current.sync)).toBe(sentence)
      expect(manualEnabled(result.current.sync)).toBe(false)
      await elapse(SYNC_POLL_MS * 5)
      expect(stateReads()).toBe(1)
    },
  )

  it("P13-f: an automatic trigger that is refused stays silent; the user's refused press holds until the session's token changes", async () => {
    stubDashboard(() => Promise.resolve(json(dashboardFixture())))
    const refusal = (): Promise<Response> =>
      Promise.resolve(json({ code: 'FORBIDDEN', message: 'x', correlationId: 'c' }, 403))
    const seen = admissions(refusal, refusal, () => Promise.resolve(json(RECENT)))
    const { result, rerender } = renderHook(({ token }) => useHarness(token), {
      initialProps: { token: CSRF },
    })
    await waitFor(() => {
      expect(result.current.sync.phase.kind).toBe('idle')
    })
    // The entry was refused: nothing is said and nothing is shut.
    expect(syncAnnouncement(result.current.sync)).toBeNull()
    expect(manualEnabled(result.current.sync)).toBe(true)

    act(() => {
      result.current.sync.requestManual()
    })
    await waitFor(() => {
      expect(result.current.sync.phase).toMatchObject({ kind: 'denied', cause: 'refused' })
    })
    expect(syncAnnouncement(result.current.sync)).toBe(copy.status.denied)
    expect(manualEnabled(result.current.sync)).toBe(false)
    act(() => {
      result.current.sync.requestManual()
    })
    await new Promise((done) => setTimeout(done, 30))
    expect(seen.triggers).toEqual(['DASHBOARD_ENTRY', 'MANUAL'])

    // A new session token lifts the refusal; the next press asks the server again.
    rerender({ token: 'csrf-renewed' })
    expect(result.current.sync.phase.kind).toBe('idle')
    act(() => {
      result.current.sync.requestManual()
    })
    await waitFor(() => {
      expect(seen.triggers).toEqual(['DASHBOARD_ENTRY', 'MANUAL', 'MANUAL'])
    })
    expect(new Headers(seen.inits[2]?.headers).get(CSRF_HEADER)).toBe('csrf-renewed')
  })

  it('P13-a: a state read that names another attempt in progress follows it; one that names a finished one ends quietly', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })
    stubDashboard(() => Promise.resolve(json(dashboardFixture())))
    admissions(
      () => Promise.resolve(json(RECENT)),
      () => Promise.resolve(admitted()),
    )
    states(
      () =>
        Promise.resolve(
          json({
            job: job({ jobId: 'job-2', status: 'RUNNING', stage: 'COLLECTION' }),
            manual: AVAILABLE,
          }),
        ),
      () =>
        Promise.resolve(
          json({
            job: job({
              jobId: 'job-3',
              status: 'FAILED',
              failureCode: 'SOURCE_UNAVAILABLE',
              retryable: true,
              finishedAt: '2026-09-27T01:00:05Z',
            }),
            manual: AVAILABLE,
          }),
        ),
    )
    const { result } = renderHook(() => useHarness(CSRF))
    await settle()
    act(() => {
      result.current.sync.requestManual()
    })
    await settle()
    await elapse(SYNC_POLL_MS)
    await settle()
    expect(result.current.sync.phase).toMatchObject({
      kind: 'syncing',
      job: { jobId: 'job-2' },
      userInitiated: true,
    })
    const reads = dashboardReads()

    await elapse(SYNC_POLL_MS)
    await settle()
    // Not a failure of this screen's attempt: nothing is claimed, no summary takes focus, the dashboard reads.
    expect(result.current.sync.phase.kind).toBe('idle')
    expect(syncAnnouncement(result.current.sync)).toBeNull()
    expect(result.current.sync.focus).toBeNull()
    expect(dashboardReads()).toBe(reads + 1)
    await elapse(SYNC_POLL_MS * 5)
    expect(stateReads()).toBe(2)
  })

  it.each([
    { name: 'the reload failed', answer: () => Promise.reject(new Error('network')) },
    {
      name: 'the reload answered an older version',
      answer: () =>
        Promise.resolve(
          json(dashboardFixture(planResultFixture({ pointerVersion: 1, calculationId: 'calc-0' }))),
        ),
    },
  ])(
    'P13-b: a publication is not announced, and focus does not move, when $name',
    async ({ answer }) => {
      vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })
      const queue: (() => Promise<Response>)[] = [
        () => Promise.resolve(json(dashboardFixture())),
        () => Promise.resolve(json(dashboardFixture())),
        answer,
      ]
      stubDashboard(() => (queue.shift() ?? (() => Promise.resolve(json(dashboardFixture()))))())
      admissions(
        () => Promise.resolve(json(RECENT)),
        () => Promise.resolve(admitted()),
      )
      states(() =>
        Promise.resolve(
          json({
            job: job({
              status: 'PUBLISHED',
              stage: 'PUBLICATION',
              mode: 'NORMAL',
              finishedAt: '2026-09-27T01:00:05Z',
              pointerVersion: 2,
            }),
            manual: AVAILABLE,
          }),
        ),
      )
      const { result } = renderHook(() => useHarness(CSRF))
      await settle()
      act(() => {
        result.current.sync.requestManual()
      })
      await settle()
      await elapse(SYNC_POLL_MS)
      await settle()
      expect(result.current.sync.phase).toMatchObject({
        kind: 'ended',
        job: { status: 'PUBLISHED' },
      })
      expect(result.current.sync.shown).toBe(false)
      expect(syncAnnouncement(result.current.sync)).toBeNull()
      expect(result.current.sync.focus).toBeNull()
      expect(pointerOf(result)).toBe(1)
    },
  )

  it('P13-d: a press while the entry request is still out says 동기화 중 at once and is sent when that request ends', async () => {
    stubDashboard(() => Promise.resolve(json(dashboardFixture())))
    const entry = deferred()
    const seen = admissions(
      () => entry.promise,
      () => Promise.resolve(admitted()),
    )
    const { result, unmount } = renderHook(() => useHarness(CSRF))
    await waitFor(() => {
      expect(seen.triggers).toEqual(['DASHBOARD_ENTRY'])
    })
    act(() => {
      result.current.sync.requestManual()
    })
    expect(result.current.sync.phase.kind).toBe('submitting')
    expect(syncAnnouncement(result.current.sync)).toBe(copy.status.syncing)
    expect(seen.triggers).toEqual(['DASHBOARD_ENTRY'])

    await act(async () => {
      entry.resolve(json(RECENT))
      await entry.promise
    })
    await waitFor(() => {
      expect(seen.triggers).toEqual(['DASHBOARD_ENTRY', 'MANUAL'])
    })
    await waitFor(() => {
      expect(result.current.sync.phase).toMatchObject({ kind: 'syncing', userInitiated: true })
    })
    unmount()
  })

  it('P13-d: a press behind an entry request that started an attempt joins that attempt as its own', async () => {
    stubDashboard(() => Promise.resolve(json(dashboardFixture())))
    const entry = deferred()
    const seen = admissions(() => entry.promise)
    const { result, unmount } = renderHook(() => useHarness(CSRF))
    await waitFor(() => {
      expect(seen.triggers).toEqual(['DASHBOARD_ENTRY'])
    })
    act(() => {
      result.current.sync.requestManual()
    })
    expect(result.current.sync.phase.kind).toBe('submitting')
    await act(async () => {
      entry.resolve(admitted({ trigger: 'DASHBOARD_ENTRY' }))
      await entry.promise
    })
    await waitFor(() => {
      expect(result.current.sync.phase).toMatchObject({ kind: 'syncing', userInitiated: true })
    })
    expect(seen.triggers).toEqual(['DASHBOARD_ENTRY'])
    unmount()
  })

  it('P13-g: a request that never answers is cut off after ten seconds and frees the button', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })
    stubDashboard(() => Promise.resolve(json(dashboardFixture())))
    const hanging = (init?: RequestInit): Promise<Response> =>
      new Promise<Response>((_, reject) => {
        init?.signal?.addEventListener('abort', () => {
          reject(new DOMException('aborted', 'AbortError'))
        })
      })
    const seen = admissions(
      () => Promise.resolve(json(RECENT)),
      hanging,
      () => Promise.resolve(admitted()),
    )
    states(hanging, hanging, hanging)
    const { result } = renderHook(() => useHarness(CSRF))
    await settle()
    act(() => {
      result.current.sync.requestManual()
    })
    await elapse(SYNC_REQUEST_TIMEOUT_MS - 1)
    expect(result.current.sync.phase.kind).toBe('submitting')
    await elapse(1)
    await settle()
    expect(result.current.sync.phase.kind).toBe('submitFailed')
    expect(manualEnabled(result.current.sync)).toBe(true)

    // Freed: the next press is sent, and a state read that hangs counts as a failed read.
    act(() => {
      result.current.sync.requestManual()
    })
    await settle()
    expect(seen.triggers).toEqual(['DASHBOARD_ENTRY', 'MANUAL', 'MANUAL'])
    for (let read = 1; read <= 3; read += 1) {
      await elapse(SYNC_POLL_MS + SYNC_REQUEST_TIMEOUT_MS)
      await settle()
    }
    expect(stateReads()).toBe(3)
    expect(result.current.sync.phase.kind).toBe('stalled')
  })

  it('a cooldown answer names its seconds and the press sends nothing until the server says otherwise', async () => {
    stubDashboard(() => Promise.resolve(json(dashboardFixture())))
    const seen = admissions(
      () => Promise.resolve(json(RECENT)),
      () =>
        Promise.resolve(
          json({
            outcome: 'COOLDOWN',
            manual: { available: false, reason: 'COOLDOWN', retryAfterSeconds: 12 },
          }),
        ),
    )
    const { result } = renderHook(() => useHarness(CSRF))
    await waitFor(() => {
      expect(result.current.sync.phase.kind).toBe('answered')
    })
    act(() => {
      result.current.sync.requestManual()
    })
    await waitFor(() => {
      expect(result.current.sync.manual?.available).toBe(false)
    })
    expect(syncAnnouncement(result.current.sync)).toBe(copy.status.cooldown(12))
    expect(manualEnabled(result.current.sync)).toBe(false)
    act(() => {
      result.current.sync.requestManual()
    })
    await new Promise((done) => setTimeout(done, 50))
    expect(seen.triggers).toEqual(['DASHBOARD_ENTRY', 'MANUAL'])
    expect(stateReads()).toBe(0)
  })

  it('a cooldown ends on its own after its seconds: the button opens and the next press asks the server again', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })
    stubDashboard(() => Promise.resolve(json(dashboardFixture())))
    const seen = admissions(
      () => Promise.resolve(json(RECENT)),
      () =>
        Promise.resolve(
          json({
            outcome: 'COOLDOWN',
            manual: { available: false, reason: 'COOLDOWN', retryAfterSeconds: 12 },
          }),
        ),
      () => Promise.resolve(json(RECENT)),
    )
    const { result } = renderHook(() => useHarness(CSRF))
    await settle()
    act(() => {
      result.current.sync.requestManual()
    })
    await settle()
    expect(manualEnabled(result.current.sync)).toBe(false)
    expect(syncAnnouncement(result.current.sync)).toBe(copy.status.cooldown(12))

    await elapse(12_000 - 1)
    expect(manualEnabled(result.current.sync)).toBe(false)
    await elapse(1)
    // Nothing says "N seconds" after the cooldown ended.
    expect(syncAnnouncement(result.current.sync)).toBeNull()
    // Unknown again, not "available": the server decides on the next press. Nothing was sent meanwhile.
    expect(result.current.sync.manual).toBeNull()
    expect(manualEnabled(result.current.sync)).toBe(true)
    expect(seen.triggers).toEqual(['DASHBOARD_ENTRY', 'MANUAL'])

    act(() => {
      result.current.sync.requestManual()
    })
    await settle()
    expect(seen.triggers).toEqual(['DASHBOARD_ENTRY', 'MANUAL', 'MANUAL'])
  })

  it('an admission already in progress (another tab) is watched, not started again', async () => {
    stubDashboard(() => Promise.resolve(json(dashboardFixture())))
    admissions(() =>
      Promise.resolve(
        json({
          outcome: 'IN_PROGRESS',
          job: job({ trigger: 'MANUAL', status: 'RUNNING', stage: 'COLLECTION' }),
          manual: { available: false, reason: 'IN_PROGRESS' },
        }),
      ),
    )
    const { result, unmount } = renderHook(() => useHarness(CSRF))
    await waitFor(() => {
      expect(result.current.sync.phase.kind).toBe('syncing')
    })
    expect(result.current.sync.phase).toMatchObject({ userInitiated: false })
    expect(syncAnnouncement(result.current.sync)).toBe(copy.status.syncing)
    unmount()
  })

  it('a long gap answer says so even for an automatic trigger, and starts nothing', async () => {
    stubDashboard(() => Promise.resolve(json(dashboardFixture())))
    admissions(() =>
      Promise.resolve(
        json({ outcome: 'LONG_GAP', manual: { available: false, reason: 'LONG_GAP' } }),
      ),
    )
    const { result } = renderHook(() => useHarness(CSRF))
    await waitFor(() => {
      expect(result.current.sync.phase).toMatchObject({ kind: 'answered', outcome: 'LONG_GAP' })
    })
    expect(syncAnnouncement(result.current.sync)).toBe(copy.status.longGap)
    expect(result.current.sync.focus).toBeNull()
    expect(stateReads()).toBe(0)
  })

  it('a press that gets no answer says so and moves focus to the summary; an automatic one stays silent', async () => {
    stubDashboard(() => Promise.resolve(json(dashboardFixture())))
    admissions(
      () => Promise.reject(new Error('network')),
      () => Promise.resolve(json({ code: 'x', message: 'x', correlationId: 'c' }, 500)),
    )
    const { result } = renderHook(() => useHarness(CSRF))
    await waitFor(() => {
      expect(result.current.sync.phase.kind).toBe('idle')
    })
    expect(syncAnnouncement(result.current.sync)).toBeNull()
    expect(result.current.sync.focus).toBeNull()

    act(() => {
      result.current.sync.requestManual()
    })
    await waitFor(() => {
      expect(result.current.sync.phase.kind).toBe('submitFailed')
    })
    expect(syncAnnouncement(result.current.sync)).toBe(copy.status.submitFailed)
    expect(result.current.sync.focus).toEqual({ target: 'summary', token: 1 })
  })

  it('reactivation: only a tab hidden for at least five minutes asks again, measured on the monotonic clock', async () => {
    Object.defineProperty(document, 'visibilityState', {
      configurable: true,
      get: () => visibility,
    })
    let clock = 1_000
    vi.spyOn(performance, 'now').mockImplementation(() => clock)
    stubDashboard(() => Promise.resolve(json(dashboardFixture())))
    const seen = admissions(
      () => Promise.resolve(json(RECENT)),
      () => Promise.resolve(json(RECENT)),
    )
    const { result } = renderHook(() => useHarness(CSRF))
    await waitFor(() => {
      expect(result.current.sync.phase.kind).toBe('answered')
    })

    // Visible without having been hidden: nothing.
    act(() => {
      setVisibility('visible')
    })
    // Hidden one millisecond short of five minutes: nothing.
    act(() => {
      setVisibility('hidden')
    })
    clock += REACTIVATION_HIDDEN_MS - 1
    act(() => {
      setVisibility('visible')
    })
    await new Promise((done) => setTimeout(done, 20))
    expect(seen.triggers).toEqual(['DASHBOARD_ENTRY'])

    // Hidden exactly five minutes: one reactivation admission.
    act(() => {
      setVisibility('hidden')
    })
    clock += REACTIVATION_HIDDEN_MS
    act(() => {
      setVisibility('visible')
    })
    await waitFor(() => {
      expect(seen.triggers).toEqual(['DASHBOARD_ENTRY', 'TAB_REACTIVATION'])
    })
    // An automatic trigger that started nothing says nothing.
    await waitFor(() => {
      expect(result.current.sync.phase.kind).toBe('answered')
    })
    expect(syncAnnouncement(result.current.sync)).toBeNull()
  })
})
