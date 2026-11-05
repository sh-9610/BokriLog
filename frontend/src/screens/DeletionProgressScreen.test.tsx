import { fireEvent, render, screen, within } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import {
  ACCOUNT_DELETION_PATH,
  ACCOUNT_DELETION_POLICY_VERSION,
  accountDeletionContent,
  accountDeletionTimeText,
  backupDeadlineText,
} from '../content/accountDeletion'
import accountDeletionPolicy from '../content/accountDeletionPolicy.fixture.json'
import {
  authContent,
  CSRF_HEADER,
  DASHBOARD_PATH,
  LOGOUT_ENDPOINT,
  noticePath,
  SESSION_ENDPOINT,
  SETTINGS_PATH,
} from '../content/auth'
import { replaceLocation } from '../session/navigation'
import { FIRST_MOUNT_TIMEOUT_MS } from '../test/firstMount'
import {
  getObservedAdConsentRequests,
  getObservedAllowedRequests,
  getObservedNoticeRequests,
  getObservedRequests,
  getObservedStorageEntries,
  getObservedSupportRequests,
  stubAccountDeletionState,
  stubLogoutRequest,
  stubSessionRequest,
} from '../test/setup'
import { SettingsScreen } from './SettingsScreen'
import { SignedInScreen } from './SignedInScreen'

/*
 * 삭제 진행 화면(Story 5.1). 세션 확인이 `deletionInProgress`를 말하면 어느 경로에서든 이 화면만 그린다 — 다른 화면의 조회는 시작하지
 * 않고 이동도 하지 않는다. 요청 시각·정책 버전·상태·백업 파기 기한과 로그아웃 하나만 있다.
 */

vi.mock('../session/navigation', () => ({ navigateTo: vi.fn(), replaceLocation: vi.fn() }))

const CSRF_TOKEN = 'deletion-progress-csrf-token'
const REQUESTED_AT = '2026-11-05T01:02:03.456Z'

function jsonResponse(body: Record<string, unknown>, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

function progressState(): Promise<Response> {
  return Promise.resolve(
    jsonResponse({
      available: true,
      policy: {
        version: accountDeletionPolicy.version,
        targets: accountDeletionPolicy.targets,
        backupDestroyDays: accountDeletionPolicy.backupDestroyDays,
        legalRetention: accountDeletionPolicy.legalRetention,
      },
      request: {
        requestId: 'del_abc',
        requestedAt: REQUESTED_AT,
        state: 'DELETION_IN_PROGRESS',
        policyVersion: ACCOUNT_DELETION_POLICY_VERSION,
      },
    }),
  )
}

beforeEach(() => {
  vi.mocked(replaceLocation).mockClear()
  stubSessionRequest(() =>
    Promise.resolve(
      jsonResponse({
        authenticated: true,
        destination: 'dashboard',
        csrfToken: CSRF_TOKEN,
        deletionInProgress: true,
      }),
    ),
  )
})

async function expectProgress(): Promise<void> {
  expect(
    await screen.findByRole(
      'heading',
      { name: accountDeletionContent.progress.title },
      { timeout: FIRST_MOUNT_TIMEOUT_MS },
    ),
  ).toBeInTheDocument()
  expect(
    await screen.findByText(accountDeletionTimeText(REQUESTED_AT), { exact: false }),
  ).toBeInTheDocument()
  expect(screen.getByText(ACCOUNT_DELETION_POLICY_VERSION)).toBeInTheDocument()
  expect(screen.getByText(accountDeletionContent.progress.state)).toBeInTheDocument()
  expect(screen.getByText(backupDeadlineText(REQUESTED_AT, 30))).toBeInTheDocument()
  // `복리 계획 비교`와 비추천 고지가 제목 바로 아래 인접해 있다.
  const title = screen.getByRole('heading', { level: 1, name: authContent.pageTitle })
  expect(title.nextElementSibling?.textContent).toBe(authContent.disclaimer)
  // 화면당 live region 하나.
  expect(screen.getAllByRole('status')).toHaveLength(1)
}

describe('삭제 진행 화면', () => {
  it('설정 경로에서도 진행 화면만 그리고 다른 조회를 시작하지 않는다', async () => {
    window.history.pushState({}, '', SETTINGS_PATH)
    stubAccountDeletionState(progressState)
    render(<SettingsScreen />)
    await expectProgress()

    expect(screen.queryByRole('region', { name: accountDeletionContent.heading })).toBeNull()
    expect(getObservedAllowedRequests()).toEqual([
      `GET ${SESSION_ENDPOINT}`,
      `GET ${ACCOUNT_DELETION_PATH}`,
    ])
    expect(getObservedSupportRequests()).toEqual([])
    expect(getObservedAdConsentRequests()).toEqual([])
    expect(getObservedNoticeRequests()).toEqual([])
    expect(getObservedRequests()).toEqual([])
    expect(vi.mocked(replaceLocation)).not.toHaveBeenCalled()
    expect(getObservedStorageEntries()).toEqual([])
  })

  it('대시보드 경로에서도 진행 화면만 그리고 로그아웃할 수 있다', async () => {
    window.history.pushState({}, '', DASHBOARD_PATH)
    stubAccountDeletionState(progressState)
    let csrf: string | null = null
    stubLogoutRequest((init) => {
      csrf = new Headers(init?.headers).get(CSRF_HEADER)
      return Promise.resolve(new Response(null, { status: 204 }))
    })
    render(<SignedInScreen />)
    await expectProgress()
    expect(getObservedRequests()).toEqual([])

    fireEvent.click(screen.getByRole('button', { name: authContent.signOut.label }))
    expect(
      await within(screen.getByRole('status')).findByText(authContent.signOut.pending),
    ).toBeInTheDocument()
    expect(getObservedAllowedRequests()).toContain(`POST ${LOGOUT_ENDPOINT}`)
    expect(csrf).toBe(CSRF_TOKEN)
  })

  it('상태를 읽지 못해도 요청이 접수됐다는 사실과 로그아웃 안내를 말한다', async () => {
    window.history.pushState({}, '', SETTINGS_PATH)
    stubAccountDeletionState(() => Promise.resolve(jsonResponse({ code: 'X' }, 500)))
    render(<SettingsScreen />)
    expect(
      await screen.findByText(accountDeletionContent.progress.unavailable, undefined, {
        timeout: FIRST_MOUNT_TIMEOUT_MS,
      }),
    ).toBeInTheDocument()
    expect(screen.getByText(accountDeletionContent.progress.signOutHint)).toBeInTheDocument()
  })

  it('세션이 끝났으면 만료 표지로 간다', async () => {
    window.history.pushState({}, '', SETTINGS_PATH)
    stubAccountDeletionState(() =>
      Promise.resolve(
        jsonResponse({ code: 'UNAUTHENTICATED', message: 'x', correlationId: 'c' }, 401),
      ),
    )
    render(<SettingsScreen />)
    await screen.findByRole(
      'heading',
      { name: accountDeletionContent.progress.title },
      { timeout: FIRST_MOUNT_TIMEOUT_MS },
    )
    await vi.waitFor(() => {
      expect(vi.mocked(replaceLocation)).toHaveBeenCalledWith(noticePath('expired'))
    })
  })

  it('다시 확인은 사용자가 누를 때만 상태를 한 번 더 읽는다', async () => {
    window.history.pushState({}, '', SETTINGS_PATH)
    stubAccountDeletionState(() => Promise.resolve(jsonResponse({ code: 'X' }, 500)))
    render(<SettingsScreen />)
    await screen.findByText(accountDeletionContent.progress.unavailable, undefined, {
      timeout: FIRST_MOUNT_TIMEOUT_MS,
    })
    const reads = (): number =>
      getObservedAllowedRequests().filter((entry) => entry === `GET ${ACCOUNT_DELETION_PATH}`)
        .length
    expect(reads()).toBe(1)

    stubAccountDeletionState(progressState)
    fireEvent.click(screen.getByRole('button', { name: accountDeletionContent.progress.retry }))
    expect(
      await screen.findByText(accountDeletionTimeText(REQUESTED_AT), { exact: false }),
    ).toBeInTheDocument()
    expect(reads()).toBe(2)
  })

  it('로그아웃이 실패하면 이 화면의 status가 그 사실을 말한다', async () => {
    window.history.pushState({}, '', SETTINGS_PATH)
    stubAccountDeletionState(progressState)
    stubLogoutRequest(() => Promise.resolve(new Response(null, { status: 403 })))
    render(<SettingsScreen />)
    await expectProgress()

    fireEvent.click(screen.getByRole('button', { name: authContent.signOut.label }))
    expect(
      await within(screen.getByRole('status')).findByText(
        accountDeletionContent.progress.signOutFailed,
      ),
    ).toBeInTheDocument()
    expect(screen.getAllByRole('status')).toHaveLength(1)
  })

  it('재인증을 요구받은 삭제 진행 세션도 회복 패널 대신 진행 화면을 그린다', async () => {
    window.history.pushState({}, '', SETTINGS_PATH)
    stubSessionRequest(() =>
      Promise.resolve(
        jsonResponse({
          authenticated: false,
          destination: 'entry',
          reauthRequired: true,
          csrfToken: CSRF_TOKEN,
          deletionInProgress: true,
        }),
      ),
    )
    stubAccountDeletionState(progressState)
    render(<SettingsScreen />)
    await expectProgress()
    expect(screen.queryByRole('heading', { name: authContent.reauth.heading })).toBeNull()
    expect(vi.mocked(replaceLocation)).not.toHaveBeenCalled()
  })
})
