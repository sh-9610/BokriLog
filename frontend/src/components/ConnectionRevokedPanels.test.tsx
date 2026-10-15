import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'

import { firstSnapshotContent } from '../content/firstSnapshot'
import { nextPlanContent } from '../content/nextPlan'
import { getObservedAllowedRequests, stubFirstSnapshotState, stubNextBaseline } from '../test/setup'
import { FirstSnapshotPanel } from './FirstSnapshotPanel'
import { NextPlanBaselinePanel } from './NextPlanBaselinePanel'

/*
 * Story 2.16: 해제 뒤 대시보드 패널은 동작하지 않는 연결·새 기준 요청을 내지 않고 중립 안내만 둔다. 안내는 다시 연결하는 자리인
 * 설정 화면을 가리킨다(Story 2.17, RECONNECT-1.0). 계획 없이 다른 계정으로 다시 연결한 뒤의 첫 확인 안내도 여기서 본다.
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

  it('다른 Bybit 계정으로 다시 연결한 뒤(계획 없음) 이전 값을 보이지 않고 새 첫 확인을 받는다 (Story 2.17)', async () => {
    stubFirstSnapshotState(() =>
      Promise.resolve(response({ connected: true, restricted: false, accountChanged: true })),
    )
    render(<FirstSnapshotPanel csrfToken="token" ready />)
    expect(await screen.findByText(firstSnapshotContent.accountChanged)).toBeInTheDocument()
    // 이전 계정의 공식값(금액)은 그리지 않는다.
    expect(screen.queryByText(/USD/)).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: firstSnapshotContent.start })).toBeInTheDocument()
  })

  it('계정 변경 안내는 새 첫 확인의 실패 문장과 함께 정적 문장으로 남고 live region을 늘리지 않는다 (Story 2.17)', async () => {
    stubFirstSnapshotState(() =>
      Promise.resolve(
        response({
          connected: true,
          restricted: false,
          accountChanged: true,
          job: { jobId: 'job-2', status: 'FAILED', failureReason: 'TIMEOUT' },
        }),
      ),
    )
    render(<FirstSnapshotPanel csrfToken="token" ready />)
    const notice = await screen.findByText(firstSnapshotContent.accountChanged)
    expect(screen.getByText(firstSnapshotContent.failed)).toBeInTheDocument()
    // eslint-disable-next-line testing-library/no-node-access -- 역할 없는 live region은 속성으로만 찾을 수 있다.
    expect(notice.closest('[aria-live]')).toBeNull()
    // eslint-disable-next-line testing-library/no-node-access -- 화면당 live region 하나를 속성으로 센다.
    expect(document.querySelectorAll('[aria-live]')).toHaveLength(1)
    expect(screen.getByRole('button', { name: firstSnapshotContent.retry })).toBeInTheDocument()
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
