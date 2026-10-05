import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'

import { firstSnapshotContent } from '../content/firstSnapshot'
import { nextPlanContent } from '../content/nextPlan'
import { getObservedAllowedRequests, stubFirstSnapshotState, stubNextBaseline } from '../test/setup'
import { FirstSnapshotPanel } from './FirstSnapshotPanel'
import { NextPlanBaselinePanel } from './NextPlanBaselinePanel'

/*
 * Story 2.16: after a revocation the screens offer no connection or new-baseline request that cannot work — reconnecting
 * is not implemented (RECONNECT-1.0) — only a neutral notice.
 */

function response(value: unknown, status = 200): Response {
  return new Response(JSON.stringify(value), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

describe('a revoked connection on the dashboard panels', () => {
  it('the first-snapshot panel says the connection was revoked and links to no connection form', async () => {
    stubFirstSnapshotState(() =>
      Promise.resolve(response({ connected: false, restricted: false, revoked: true })),
    )
    render(<FirstSnapshotPanel csrfToken="token" ready />)
    expect(await screen.findByText(firstSnapshotContent.revoked)).toBeInTheDocument()
    expect(screen.queryByText(firstSnapshotContent.unconnected)).not.toBeInTheDocument()
    expect(
      screen.queryByRole('link', { name: firstSnapshotContent.connectionSettings }),
    ).not.toBeInTheDocument()
    expect(
      screen.queryByRole('button', { name: firstSnapshotContent.start }),
    ).not.toBeInTheDocument()
  })

  it('a first official value read before the revocation stays shown, read-only, with no new read', async () => {
    stubFirstSnapshotState(() =>
      Promise.resolve(
        response({
          connected: false,
          restricted: false,
          revoked: true,
          job: { jobId: 'job-1', status: 'SUCCEEDED' },
          decimal: '1250.5',
          currency: 'USD',
          measuredAt: '2026-10-01T00:00:00Z',
        }),
      ),
    )
    render(<FirstSnapshotPanel csrfToken="token" ready />)
    expect(await screen.findByText(firstSnapshotContent.revoked)).toBeInTheDocument()
    expect(screen.getByText(/1,250\.50 USD|1250\.50 USD/)).toBeInTheDocument()
    expect(screen.queryByRole('button')).not.toBeInTheDocument()
    expect(
      screen.queryByRole('link', { name: firstSnapshotContent.connectionSettings }),
    ).not.toBeInTheDocument()
  })

  it('a never-connected account still gets the way to the connection settings', async () => {
    stubFirstSnapshotState(() => Promise.resolve(response({ connected: false, restricted: false })))
    render(<FirstSnapshotPanel csrfToken="token" ready />)
    expect(await screen.findByText(firstSnapshotContent.unconnected)).toBeInTheDocument()
    expect(
      screen.getByRole('link', { name: firstSnapshotContent.connectionSettings }),
    ).toBeInTheDocument()
  })

  it('the new-baseline panel says why no baseline can be read and offers no request', async () => {
    stubNextBaseline(() =>
      Promise.resolve(
        response({
          eligible: false,
          reason: 'CONNECTION_REVOKED',
          job: null,
          baseline: null,
          snapshot: null,
        }),
      ),
    )
    render(<NextPlanBaselinePanel csrfToken="token" />)
    fireEvent.click(screen.getByRole('button', { name: nextPlanContent.inspect }))
    expect(await screen.findByText(nextPlanContent.revoked)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: nextPlanContent.prepare })).not.toBeInTheDocument()
    expect(getObservedAllowedRequests()).not.toContain('POST /api/plans/next/baseline')
  })
})
