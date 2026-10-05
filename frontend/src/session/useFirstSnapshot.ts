import { useCallback, useEffect, useRef, useState } from 'react'

import { CSRF_HEADER } from '../content/auth'
import { FIRST_SNAPSHOT_PATH } from '../content/firstSnapshot'

export interface SnapshotJob {
  readonly jobId: string
  readonly status: 'QUEUED' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'BLOCKED'
  readonly failureReason?: string
}
export interface SnapshotState {
  readonly connected: boolean
  readonly restricted: boolean
  readonly job?: SnapshotJob
  readonly decimal?: string
  readonly currency?: string
  readonly measuredAt?: string
  readonly composedAt?: string
  readonly publishedAt?: string
  readonly snapshotId?: string
  /** The user revoked the connection (Story 2.16). Present only then. */
  readonly revoked?: boolean
}
export type SnapshotView =
  | { readonly kind: 'checking' | 'unconnected' | 'unavailable' | 'revoked' }
  | { readonly kind: 'loaded'; readonly state: SnapshotState }

const isJob = (value: unknown): value is SnapshotJob => {
  if (typeof value !== 'object' || value === null) return false
  const row = value as Record<string, unknown>
  return (
    typeof row.jobId === 'string' &&
    ['QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'BLOCKED'].includes(String(row.status)) &&
    (row.failureReason === undefined || typeof row.failureReason === 'string')
  )
}
const isState = (value: unknown): value is SnapshotState => {
  if (typeof value !== 'object' || value === null) return false
  const row = value as Record<string, unknown>
  return (
    typeof row.connected === 'boolean' &&
    typeof row.restricted === 'boolean' &&
    (row.job === undefined || isJob(row.job)) &&
    (row.decimal === undefined || typeof row.decimal === 'string') &&
    (row.measuredAt === undefined || typeof row.measuredAt === 'string') &&
    (row.revoked === undefined || typeof row.revoked === 'boolean')
  )
}

/** Financial values live only in React memory. Polling stops when the first job reaches a terminal state. */
export function useFirstSnapshot(
  csrfToken: string | null,
  ready: boolean,
): {
  readonly view: SnapshotView
  readonly pending: boolean
  readonly start: () => void
  readonly refresh: () => void
} {
  const [view, setView] = useState<SnapshotView>({ kind: 'checking' })
  const [pending, setPending] = useState(false)
  const [revision, setRevision] = useState(0)
  const inFlight = useRef(false)
  const refresh = useCallback(() => {
    setRevision((n) => n + 1)
  }, [])

  // The read stays a passive effect: starting a request is not something the commit shows, and its answer comes
  // on a later task anyway. The next read's timer is set from that answer, as part of the same chain of requests.
  useEffect(() => {
    if (!ready) return
    let current = true
    let timer: ReturnType<typeof setTimeout> | undefined
    const read = async (): Promise<void> => {
      try {
        const response = await fetch(FIRST_SNAPSHOT_PATH, {
          credentials: 'same-origin',
          cache: 'no-store',
          headers: { Accept: 'application/json' },
        })
        if (!current) return
        if (response.status === 403) {
          setView({ kind: 'unconnected' })
          return
        }
        if (!response.ok) throw new Error('snapshot request failed')
        const payload: unknown = await response.json()
        if (!isState(payload)) throw new Error('invalid snapshot state')
        // A revoked connection is its own state (Story 2.16): reconnecting is not offered, only a notice.
        // A revoked connection keeps its first official value read-only: shown with the revoked flag, no new read.
        setView(
          payload.connected || (payload.revoked === true && payload.decimal !== undefined)
            ? { kind: 'loaded', state: payload }
            : payload.revoked === true
              ? { kind: 'revoked' }
              : { kind: 'unconnected' },
        )
        if (payload.job?.status === 'QUEUED' || payload.job?.status === 'RUNNING') {
          timer = setTimeout(() => {
            void read()
          }, 1000)
        }
      } catch {
        if (current) setView({ kind: 'unavailable' })
      }
    }
    void read()
    return () => {
      current = false
      if (timer) clearTimeout(timer)
    }
  }, [ready, revision])

  const start = useCallback((): void => {
    if (!ready || inFlight.current || csrfToken === null) return
    inFlight.current = true
    setPending(true)
    void (async () => {
      try {
        const response = await fetch(FIRST_SNAPSHOT_PATH, {
          method: 'POST',
          credentials: 'same-origin',
          cache: 'no-store',
          headers: { [CSRF_HEADER]: csrfToken, Accept: 'application/json' },
        })
        if (!response.ok) throw new Error('snapshot admission failed')
        const payload: unknown = await response.json()
        if (!isJob(payload)) throw new Error('invalid job')
        setView({ kind: 'loaded', state: { connected: true, restricted: false, job: payload } })
        setTimeout(refresh, 1000)
      } catch {
        setView({ kind: 'unavailable' })
      } finally {
        inFlight.current = false
        setPending(false)
      }
    })()
  }, [csrfToken, ready, refresh])
  return { view, pending, start, refresh }
}
