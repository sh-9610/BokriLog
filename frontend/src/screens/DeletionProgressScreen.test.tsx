import { fireEvent, render, screen, within } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import {
  ACCOUNT_DELETION_PATH,
  ACCOUNT_DELETION_POLICY_VERSION,
  ACCOUNT_DELETION_RETRY_PATH,
  ACCOUNT_DELETION_STEPS,
  accountDeletionContent,
  accountDeletionRefusalMessage,
  accountDeletionStepScope,
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
import { FORBIDDEN_COPY_WORDS } from '../content/copyRules'
import { ACCOUNT_DELETION_PROGRESS_STATUSES, retryDeletion } from '../session/accountDeletion'
import { replaceLocation } from '../session/navigation'
import { FIRST_MOUNT_TIMEOUT_MS } from '../test/firstMount'
import {
  getObservedAllowedRequests,
  getObservedDashboardAdRequests,
  getObservedNoticeRequests,
  getObservedRequests,
  getObservedStorageEntries,
  getObservedSupportRequests,
  stubAccountDeletionRetry,
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
        // 서버의 실제 모양(Story 5.2): Worker가 집기 전·실행 중은 진행 중이고 실패 단계가 없다.
        progress: { status: 'IN_PROGRESS', failedSteps: [] },
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
  // 요청 + 30일은 내부 목표일이다 — 약속한 기한이 아님을 레이블과 설명이 말한다.
  expect(screen.getByText(accountDeletionContent.progress.backupLabel)).toBeInTheDocument()
  expect(screen.getByText(accountDeletionContent.progress.backupTargetNote)).toBeInTheDocument()
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
    expect(getObservedDashboardAdRequests()).toEqual([])
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

/*
 * 부분 실패와 다시 시도(Story 5.2). 실행이 한 단계에서 멈추면 `재시도 필요`와 지우지 못한 범위(닫힌 맵)·`삭제 다시 시도`를 보여 준다.
 * 다시 시도는 누를 때만 보내고 CSRF·멱등 키를 싣는다. 접수되면 상태를 한 번 더 읽고, 응답이 없으면 같은 키로 다시 보낸다. 자동
 * 재시도·주기 폴링은 없다.
 */

function retryNeededState(failedSteps: readonly string[]): () => Promise<Response> {
  return () =>
    Promise.resolve(
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
          progress: { status: 'RETRY_NEEDED', failedSteps },
        },
      }),
    )
}

describe('삭제 진행 화면의 부분 실패와 다시 시도', () => {
  it('단계 code가 공유 표와 같고 범위 문장에 금지 표현이 없다', () => {
    expect([...ACCOUNT_DELETION_STEPS]).toEqual(accountDeletionPolicy.erasureSteps)
    expect(ACCOUNT_DELETION_RETRY_PATH).toBe(accountDeletionPolicy.retryPath)
    for (const step of ACCOUNT_DELETION_STEPS) {
      for (const word of FORBIDDEN_COPY_WORDS) {
        expect(accountDeletionStepScope(step)).not.toContain(word)
      }
    }
    // 모르는 code는 범위를 지어내지 않고 하나로 접힌다.
    expect(accountDeletionStepScope('SOMETHING_NEW')).toBe(accountDeletionStepScope('ANOTHER'))
  })

  it('재시도 필요면 실패 범위와 다시 시도를 보여 주고 누르면 접수 뒤 상태를 다시 읽는다', async () => {
    window.history.pushState({}, '', SETTINGS_PATH)
    stubAccountDeletionState(retryNeededState(['SYNC', 'OPERATIONS']))
    const sent: { csrf: string | null; key: string | null; body: unknown }[] = []
    stubAccountDeletionRetry((init) => {
      const headers = new Headers(init?.headers)
      sent.push({
        csrf: headers.get(CSRF_HEADER),
        key: headers.get('Idempotency-Key'),
        body: init?.body ?? null,
      })
      return Promise.resolve(jsonResponse({}, 202))
    })
    render(<SettingsScreen />)
    expect(
      await screen.findByText(accountDeletionContent.progress.stateRetryNeeded, undefined, {
        timeout: FIRST_MOUNT_TIMEOUT_MS,
      }),
    ).toBeInTheDocument()
    const scope = screen.getByRole('list', {
      name: accountDeletionContent.progress.failedScopeHeading,
    })
    expect(
      within(scope)
        .getAllByRole('listitem')
        .map((item) => item.textContent),
    ).toEqual([accountDeletionStepScope('SYNC'), accountDeletionStepScope('OPERATIONS')])
    expect(screen.getByText(accountDeletionContent.progress.retryNeededBody)).toBeInTheDocument()
    // 자동으로 보내지 않는다.
    expect(sent).toEqual([])
    expect(screen.getAllByRole('status')).toHaveLength(1)

    stubAccountDeletionState(progressState)
    fireEvent.click(
      screen.getByRole('button', { name: accountDeletionContent.progress.retryErasure }),
    )
    expect(
      await within(screen.getByRole('status')).findByText(
        accountDeletionContent.progress.retryAccepted,
      ),
    ).toBeInTheDocument()
    expect(sent).toHaveLength(1)
    expect(sent[0]?.csrf).toBe(CSRF_TOKEN)
    expect(sent[0]?.key).toMatch(/^[0-9a-f-]{36}$/)
    expect(sent[0]?.body).toBeNull()
    // 접수 뒤 상태를 한 번 더 읽었다 — 진행 중으로 돌아왔다.
    expect(await screen.findByText(accountDeletionContent.progress.state)).toBeInTheDocument()
    expect(
      screen.queryByRole('button', { name: accountDeletionContent.progress.retryErasure }),
    ).toBeNull()
    expect(getObservedStorageEntries()).toEqual([])
  })

  it('응답이 없으면 같은 키로 다시 보내고 거절은 닫힌 문장으로 말한다', async () => {
    window.history.pushState({}, '', SETTINGS_PATH)
    stubAccountDeletionState(retryNeededState(['IDENTITY']))
    const keys: (string | null)[] = []
    let answer: () => Promise<Response> = () => Promise.reject(new TypeError('network'))
    stubAccountDeletionRetry((init) => {
      keys.push(new Headers(init?.headers).get('Idempotency-Key'))
      return answer()
    })
    render(<SettingsScreen />)
    const button = await screen.findByRole(
      'button',
      { name: accountDeletionContent.progress.retryErasure },
      { timeout: FIRST_MOUNT_TIMEOUT_MS },
    )
    fireEvent.click(button)
    expect(
      await within(screen.getByRole('status')).findByText(
        accountDeletionContent.progress.retryUnanswered,
      ),
    ).toBeInTheDocument()

    answer = () =>
      Promise.resolve(
        jsonResponse(
          { code: 'ACCOUNT_DELETION_NOT_RETRYABLE', message: 'x', correlationId: 'c' },
          409,
        ),
      )
    // 거절 뒤에도 접수와 같이 상태를 한 번 다시 읽는다 — 이미 다시 도는 중이라 진행 중이다.
    stubAccountDeletionState(progressState)
    fireEvent.click(
      screen.getByRole('button', { name: accountDeletionContent.progress.retryErasure }),
    )
    expect(
      await within(screen.getByRole('status')).findByText(
        accountDeletionRefusalMessage('ACCOUNT_DELETION_NOT_RETRYABLE'),
      ),
    ).toBeInTheDocument()
    expect(keys).toHaveLength(2)
    expect(keys[1]).toBe(keys[0])
    expect(await screen.findByText(accountDeletionContent.progress.state)).toBeInTheDocument()
    expect(
      getObservedAllowedRequests().filter((entry) => entry === `GET ${ACCOUNT_DELETION_PATH}`),
    ).toHaveLength(2)
    // 새 상태가 진행 중이면 거절 문장은 그대로 남는다(한 live region).
    expect(
      within(screen.getByRole('status')).getByText(
        accountDeletionRefusalMessage('ACCOUNT_DELETION_NOT_RETRYABLE'),
      ),
    ).toBeInTheDocument()
    expect(screen.getAllByRole('status')).toHaveLength(1)
  })

  it('다시 읽은 상태가 여전히 재시도 필요면 접수 문장을 남기지 않는다', async () => {
    window.history.pushState({}, '', SETTINGS_PATH)
    stubAccountDeletionState(retryNeededState(['SYNC']))
    stubAccountDeletionRetry(() => Promise.resolve(jsonResponse({}, 202)))
    render(<SettingsScreen />)
    fireEvent.click(
      await screen.findByRole(
        'button',
        { name: accountDeletionContent.progress.retryErasure },
        { timeout: FIRST_MOUNT_TIMEOUT_MS },
      ),
    )
    await vi.waitFor(() => {
      expect(
        getObservedAllowedRequests().filter((entry) => entry === `GET ${ACCOUNT_DELETION_PATH}`),
      ).toHaveLength(2)
    })
    await vi.waitFor(() => {
      expect(screen.getByRole('status')).toHaveAttribute('aria-busy', 'false')
    })
    expect(
      within(screen.getByRole('status')).queryByText(accountDeletionContent.progress.retryAccepted),
    ).toBeNull()
    expect(screen.getByText(accountDeletionContent.progress.stateRetryNeeded)).toBeInTheDocument()
  })

  it('다시 읽기에 실패하면 접수 문장 대신 읽지 못했다고 말한다', async () => {
    window.history.pushState({}, '', SETTINGS_PATH)
    stubAccountDeletionState(retryNeededState(['SYNC']))
    stubAccountDeletionRetry(() => Promise.resolve(jsonResponse({}, 202)))
    render(<SettingsScreen />)
    const button = await screen.findByRole(
      'button',
      { name: accountDeletionContent.progress.retryErasure },
      { timeout: FIRST_MOUNT_TIMEOUT_MS },
    )
    stubAccountDeletionState(() => Promise.resolve(jsonResponse({ code: 'X' }, 500)))
    fireEvent.click(button)
    expect(
      await within(screen.getByRole('status')).findByText(
        accountDeletionContent.progress.unavailable,
      ),
    ).toBeInTheDocument()
    expect(
      within(screen.getByRole('status')).queryByText(accountDeletionContent.progress.retryAccepted),
    ).toBeNull()
  })

  it('무응답 뒤 사용자가 다시 확인해 새 상태를 읽으면 다음 다시 시도는 새 키다', async () => {
    window.history.pushState({}, '', SETTINGS_PATH)
    stubAccountDeletionState(retryNeededState(['PLAN']))
    const keys: (string | null)[] = []
    stubAccountDeletionRetry((init) => {
      keys.push(new Headers(init?.headers).get('Idempotency-Key'))
      return Promise.reject(new TypeError('network'))
    })
    render(<SettingsScreen />)
    fireEvent.click(
      await screen.findByRole(
        'button',
        { name: accountDeletionContent.progress.retryErasure },
        { timeout: FIRST_MOUNT_TIMEOUT_MS },
      ),
    )
    await within(screen.getByRole('status')).findByText(
      accountDeletionContent.progress.retryUnanswered,
    )
    fireEvent.click(screen.getByRole('button', { name: accountDeletionContent.progress.retry }))
    await vi.waitFor(() => {
      expect(
        getObservedAllowedRequests().filter((entry) => entry === `GET ${ACCOUNT_DELETION_PATH}`),
      ).toHaveLength(2)
    })
    fireEvent.click(
      await screen.findByRole('button', { name: accountDeletionContent.progress.retryErasure }),
    )
    await vi.waitFor(() => {
      expect(keys).toHaveLength(2)
    })
    expect(keys[1]).not.toBe(keys[0])
  })

  it('운영 데이터 삭제 완료 상태는 백업 파기와 구분해 말하고 다시 시도가 없다', async () => {
    window.history.pushState({}, '', SETTINGS_PATH)
    stubAccountDeletionState(() =>
      Promise.resolve(
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
            progress: { status: 'OPERATIONAL_DATA_ERASED', failedSteps: [] },
          },
        }),
      ),
    )
    render(<SettingsScreen />)
    expect(
      await screen.findByText(accountDeletionContent.progress.stateErased, undefined, {
        timeout: FIRST_MOUNT_TIMEOUT_MS,
      }),
    ).toBeInTheDocument()
    expect(
      screen.queryByRole('button', { name: accountDeletionContent.progress.retryErasure }),
    ).toBeNull()
  })

  it('모르는 단계 code는 한 문장으로 접혀 한 번만 말한다', async () => {
    window.history.pushState({}, '', SETTINGS_PATH)
    stubAccountDeletionState(retryNeededState(['SOMETHING_NEW', 'SYNC', 'ANOTHER_NEW']))
    render(<SettingsScreen />)
    const scope = await screen.findByRole(
      'list',
      { name: accountDeletionContent.progress.failedScopeHeading },
      { timeout: FIRST_MOUNT_TIMEOUT_MS },
    )
    expect(
      within(scope)
        .getAllByRole('listitem')
        .map((item) => item.textContent),
    ).toEqual([accountDeletionStepScope('SOMETHING_NEW'), accountDeletionStepScope('SYNC')])
  })

  it('진행 상태 값이 공유 표와 같다', () => {
    expect([...ACCOUNT_DELETION_PROGRESS_STATUSES]).toEqual(accountDeletionPolicy.progressStatuses)
  })

  it('다시 시도의 공통 본문 없는 5xx와 502·503·504는 무응답이고 공통 본문 5xx는 거절이다', async () => {
    const answers: Response[] = [
      new Response('upstream', { status: 500 }),
      jsonResponse({ code: 'UPSTREAM_UNAVAILABLE', message: 'x', correlationId: 'c' }, 503),
      jsonResponse({ code: 'INTERNAL_ERROR', message: 'x', correlationId: 'c' }, 500),
    ]
    stubAccountDeletionRetry(() => Promise.resolve(answers.shift() ?? jsonResponse({}, 202)))
    expect(await retryDeletion(CSRF_TOKEN, 'k1')).toEqual({ outcome: 'unanswered' })
    expect(await retryDeletion(CSRF_TOKEN, 'k1')).toEqual({ outcome: 'unanswered' })
    expect(await retryDeletion(CSRF_TOKEN, 'k1')).toEqual({
      outcome: 'failed',
      code: 'INTERNAL_ERROR',
    })
  })

  it('다시 시도에서 세션이 끝났으면 만료 표지로 간다', async () => {
    window.history.pushState({}, '', SETTINGS_PATH)
    stubAccountDeletionState(retryNeededState(['ENGAGEMENT']))
    stubAccountDeletionRetry(() =>
      Promise.resolve(
        jsonResponse({ code: 'UNAUTHENTICATED', message: 'x', correlationId: 'c' }, 401),
      ),
    )
    render(<SettingsScreen />)
    fireEvent.click(
      await screen.findByRole(
        'button',
        { name: accountDeletionContent.progress.retryErasure },
        { timeout: FIRST_MOUNT_TIMEOUT_MS },
      ),
    )
    await vi.waitFor(() => {
      expect(vi.mocked(replaceLocation)).toHaveBeenCalledWith(noticePath('expired'))
    })
  })
})

/** 진행 필드의 모양(Story 5.2 2차 리뷰): 서버는 언제나 싣는다 — 없거나 모르는 모양이면 상태를 읽지 못한 것으로 접는다. */
function stateWithRequest(request: Record<string, unknown>): () => Promise<Response> {
  return () =>
    Promise.resolve(
      jsonResponse({
        available: true,
        policy: {
          version: accountDeletionPolicy.version,
          targets: accountDeletionPolicy.targets,
          backupDestroyDays: accountDeletionPolicy.backupDestroyDays,
          legalRetention: accountDeletionPolicy.legalRetention,
        },
        request: {
          requestId: 'del_shape',
          requestedAt: REQUESTED_AT,
          state: 'DELETION_IN_PROGRESS',
          policyVersion: ACCOUNT_DELETION_POLICY_VERSION,
          ...request,
        },
      }),
    )
}

describe('삭제 진행 응답의 진행 모양', () => {
  it.each([
    ['진행이 없다', {}],
    ['모르는 진행 상태', { progress: { status: 'SOMETHING_ELSE', failedSteps: [] } }],
    [
      '실패 단계가 문자열 배열이 아니다',
      { progress: { status: 'RETRY_NEEDED', failedSteps: [1] } },
    ],
    ['실패 단계가 배열이 아니다', { progress: { status: 'RETRY_NEEDED', failedSteps: 'SYNC' } }],
  ])('%s면 상태를 읽지 못한 것으로 접는다', async (_name, request) => {
    window.history.pushState({}, '', SETTINGS_PATH)
    stubAccountDeletionState(stateWithRequest(request))
    render(<SettingsScreen />)
    expect(
      await screen.findByText(accountDeletionContent.progress.unavailable, undefined, {
        timeout: FIRST_MOUNT_TIMEOUT_MS,
      }),
    ).toBeInTheDocument()
    expect(
      screen.queryByRole('button', { name: accountDeletionContent.progress.retryErasure }),
    ).toBeNull()
  })

  it('화면을 떠난 뒤 도착한 다시 시도 응답은 상태도 이동도 바꾸지 않는다', async () => {
    window.history.pushState({}, '', SETTINGS_PATH)
    stubAccountDeletionState(retryNeededState(['SYNC']))
    const pending: { resolve?: (response: Response) => void } = {}
    stubAccountDeletionRetry(
      () =>
        new Promise<Response>((resolve) => {
          pending.resolve = resolve
        }),
    )
    const view = render(<SettingsScreen />)
    fireEvent.click(
      await screen.findByRole(
        'button',
        { name: accountDeletionContent.progress.retryErasure },
        { timeout: FIRST_MOUNT_TIMEOUT_MS },
      ),
    )
    await vi.waitFor(() => {
      expect(getObservedAllowedRequests()).toContain(`POST ${ACCOUNT_DELETION_RETRY_PATH}`)
    })
    view.unmount()
    pending.resolve?.(
      jsonResponse({ code: 'UNAUTHENTICATED', message: 'x', correlationId: 'c' }, 401),
    )
    await new Promise((resolve) => setTimeout(resolve, 20))
    expect(vi.mocked(replaceLocation)).not.toHaveBeenCalled()
  })
})
