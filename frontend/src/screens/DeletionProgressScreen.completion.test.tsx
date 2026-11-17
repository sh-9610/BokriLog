import { fireEvent, render, screen, within } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import {
  ACCOUNT_DELETION_PATH,
  ACCOUNT_DELETION_POLICY_VERSION,
  accountDeletionContent,
  accountDeletionRefusalMessage,
  accountDeletionTimeText,
  backupDestructionText,
  completionStateText,
} from '../content/accountDeletion'
import accountDeletionPolicy from '../content/accountDeletionPolicy.fixture.json'
import {
  authContent,
  CSRF_HEADER,
  LOGOUT_ENDPOINT,
  noticeKindOf,
  noticePath,
  SESSION_ENDPOINT,
  SETTINGS_PATH,
} from '../content/auth'
import { FORBIDDEN_COPY_WORDS } from '../content/copyRules'
import {
  BACKUP_DESTRUCTION_STATUSES,
  parseDeletionCompletion,
  parseDeletionState,
} from '../session/accountDeletion'
import { replaceLocation } from '../session/navigation'
import { FIRST_MOUNT_TIMEOUT_MS } from '../test/firstMount'
import {
  getObservedAllowedRequests,
  getObservedRequests,
  getObservedStorageEntries,
  stubAccountDeletionRetry,
  stubAccountDeletionState,
  stubLogoutRequest,
  stubSessionRequest,
} from '../test/setup'
import { EntryScreen } from './EntryScreen'
import { SettingsScreen } from './SettingsScreen'
import { SignedInScreen } from './SignedInScreen'

/*
 * `DeletionProgressScreen`의 완료 상태(Story 5.4) — 같은 화면 컴포넌트를 완료 확인 세션으로 그린다(진행 상태 시험은
 * `DeletionProgressScreen.test.tsx`). 삭제 완료 안내. 삭제가 끝나면 요청 세션은 계정 정보 없는 완료 확인 세션이 되고(세션 확인은 미인증이지만
 * `deletionCompleted`와 로그아웃용 토큰을 싣는다), 같은 삭제 상태 조회가 완료 조회(`completion`)를 돌려준다 — 진행 화면이 그 자리에서
 * 완료 화면이 된다. 모든 값은 서버 값이고 기한 초과를 숨기지 않으며, 설정된 지원 연락처를 진행·완료 화면에 둔다(deferred 170).
 */

vi.mock('../session/navigation', () => ({ navigateTo: vi.fn(), replaceLocation: vi.fn() }))

const CSRF_TOKEN = 'deletion-completion-csrf-token'
const REQUESTED_AT = '2026-11-05T01:02:03.456Z'
const ERASED_AT = '2026-11-05T02:03:04.567Z'
const DEADLINE = '2026-12-05T01:02:03.456Z'
const DESTROYED_AT = '2026-12-01T05:06:07.890Z'
const COMPLETED_AT = '2026-12-01T05:11:12.345Z'
const SUPPORT = 'support@bokrilog.test'

/** 재시도 필요로 멈춘 진행 중 요청(Story 5.2). */
function retryNeeded(): Promise<Response> {
  return Promise.resolve(
    jsonResponse({
      available: true,
      policy: POLICY,
      request: {
        requestId: 'del_retry',
        requestedAt: REQUESTED_AT,
        state: 'DELETION_IN_PROGRESS',
        policyVersion: ACCOUNT_DELETION_POLICY_VERSION,
        progress: { status: 'RETRY_NEEDED', failedSteps: ['SYNC'] },
      },
    }),
  )
}

function jsonResponse(body: Record<string, unknown>, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

const POLICY = {
  version: accountDeletionPolicy.version,
  targets: accountDeletionPolicy.targets,
  backupDestroyDays: accountDeletionPolicy.backupDestroyDays,
  legalRetention: accountDeletionPolicy.legalRetention,
}

function completionBody(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    requestId: 'del_done',
    requestedAt: REQUESTED_AT,
    operationalErasedAt: ERASED_AT,
    backupDeadline: DEADLINE,
    backupDestruction: 'PENDING',
    backupDestroyedAt: null,
    deletionCompleted: false,
    deletionCompletedAt: null,
    ...overrides,
  }
}

function completionState(
  overrides: Record<string, unknown> = {},
  supportContact: string | null = SUPPORT,
): () => Promise<Response> {
  return () =>
    Promise.resolve(
      jsonResponse({
        available: true,
        policy: POLICY,
        request: null,
        completion: completionBody(overrides),
        ...(supportContact === null ? {} : { supportContact }),
      }),
    )
}

/** 완료 확인 전용 세션의 세션 확인 응답 — 미인증이지만 완료 표지와 토큰이 있다. */
function completionSession(): void {
  stubSessionRequest(() =>
    Promise.resolve(
      jsonResponse({
        authenticated: false,
        destination: 'entry',
        csrfToken: CSRF_TOKEN,
        deletionCompleted: true,
      }),
    ),
  )
}

beforeEach(() => {
  vi.mocked(replaceLocation).mockClear()
  completionSession()
})

async function findCompletionHeading(name: string): Promise<HTMLElement> {
  return screen.findByRole('heading', { name }, { timeout: FIRST_MOUNT_TIMEOUT_MS })
}

describe('삭제 완료 안내', () => {
  it('완료 세션은 어느 경로에서든 완료 화면을 그리고 서버 값만 말하며 만료로 보내지 않는다', async () => {
    window.history.pushState({}, '', SETTINGS_PATH)
    stubAccountDeletionState(completionState())
    render(<SettingsScreen />)

    await findCompletionHeading(accountDeletionContent.completion.title)
    const copy = accountDeletionContent.completion
    // 제목은 세션의 완료 표지로 먼저 서고, 값은 완료 조회를 읽은 뒤에 실린다.
    expect(await screen.findByText(accountDeletionTimeText(ERASED_AT))).toBeInTheDocument()
    expect(screen.getByText(copy.body)).toBeInTheDocument()
    // 백업 사본 파기 목표일도 시각까지 말한다(서버 값 그대로). 내부 목표이고 약속한 기한이 아님을 함께 말한다.
    expect(screen.getByText(accountDeletionTimeText(DEADLINE))).toBeInTheDocument()
    expect(screen.getByText(copy.backupDeadlineLabel)).toBeInTheDocument()
    expect(screen.getByText(accountDeletionContent.progress.backupTargetNote)).toBeInTheDocument()
    // 요청 ID는 문의할 때 알려 줄 값이다.
    expect(screen.getByText('del_done')).toBeInTheDocument()
    expect(screen.getByText(accountDeletionContent.progress.requestIdLabel)).toBeInTheDocument()
    expect(screen.getByText(copy.backupPending)).toBeInTheDocument()
    expect(screen.getByText(copy.completionPending)).toBeInTheDocument()
    expect(screen.getByText(copy.legalNone)).toBeInTheDocument()
    expect(screen.getByText(accountDeletionContent.progress.stateErased)).toBeInTheDocument()
    expect(screen.getByText(copy.limitNotice)).toBeInTheDocument()
    // 다시 시도는 없다 — 완료 세션이 할 수 있는 것은 확인과 로그아웃뿐이다.
    expect(
      screen.queryByRole('button', { name: accountDeletionContent.progress.retryErasure }),
    ).toBeNull()
    expect(screen.getByRole('button', { name: copy.retry })).toBeInTheDocument()
    expect(screen.getByText(copy.signOutHint)).toBeInTheDocument()
    // 지원 경로(deferred 170): 서버가 준 연락처 그대로다.
    const link = screen.getByRole('link', { name: SUPPORT })
    expect(link).toHaveAttribute('href', `mailto:${SUPPORT}`)
    // 고지 인접·live region 하나·만료 이동 없음·다른 조회 없음·저장소 없음.
    const title = screen.getByRole('heading', { level: 1, name: authContent.pageTitle })
    expect(title.nextElementSibling?.textContent).toBe(authContent.disclaimer)
    expect(screen.getAllByRole('status')).toHaveLength(1)
    expect(vi.mocked(replaceLocation)).not.toHaveBeenCalled()
    expect(getObservedAllowedRequests()).toEqual([
      `GET ${SESSION_ENDPOINT}`,
      `GET ${ACCOUNT_DELETION_PATH}`,
    ])
    expect(getObservedRequests()).toEqual([])
    expect(getObservedStorageEntries()).toEqual([])
  })

  it('완료 세션은 받은 토큰으로 로그아웃한다', async () => {
    window.history.pushState({}, '', SETTINGS_PATH)
    stubAccountDeletionState(completionState())
    let csrf: string | null = null
    stubLogoutRequest((init) => {
      csrf = new Headers(init?.headers).get(CSRF_HEADER)
      return Promise.resolve(new Response(null, { status: 204 }))
    })
    render(<SignedInScreen />)
    await findCompletionHeading(accountDeletionContent.completion.title)

    fireEvent.click(screen.getByRole('button', { name: authContent.signOut.label }))
    expect(
      await within(screen.getByRole('status')).findByText(authContent.signOut.pending),
    ).toBeInTheDocument()
    expect(getObservedAllowedRequests()).toContain(`POST ${LOGOUT_ENDPOINT}`)
    expect(csrf).toBe(CSRF_TOKEN)
  })

  it('백업 사본 파기 목표일 초과를 숨기지 않고 완료로 말하지 않는다', async () => {
    window.history.pushState({}, '', SETTINGS_PATH)
    stubAccountDeletionState(completionState({ backupDestruction: 'OVERDUE' }))
    render(<SettingsScreen />)

    await findCompletionHeading(accountDeletionContent.completion.title)
    const copy = accountDeletionContent.completion
    expect(await screen.findByText(copy.backupOverdue)).toBeInTheDocument()
    expect(screen.getByText(copy.overdueNotice)).toBeInTheDocument()
    // 상태 줄은 초과를 "기한 안"으로 말하지 않는다.
    expect(screen.getByText(copy.stateErasedOverdue)).toBeInTheDocument()
    expect(screen.queryByText(accountDeletionContent.progress.stateErased)).toBeNull()
    expect(screen.getByText(copy.completionPending)).toBeInTheDocument()
    expect(screen.queryByText(copy.completionDone)).toBeNull()
  })

  it('전체 삭제 완료는 백업 사본 파기 완료와 함께 그 시각들을 말한다', async () => {
    window.history.pushState({}, '', SETTINGS_PATH)
    stubAccountDeletionState(
      completionState({
        backupDestruction: 'DESTROYED',
        backupDestroyedAt: DESTROYED_AT,
        deletionCompleted: true,
        deletionCompletedAt: COMPLETED_AT,
      }),
    )
    render(<SettingsScreen />)

    await findCompletionHeading(accountDeletionContent.completion.titleCompleted)
    const copy = accountDeletionContent.completion
    expect(screen.getByText(copy.backupDestroyed)).toBeInTheDocument()
    expect(screen.getByText(accountDeletionTimeText(DESTROYED_AT))).toBeInTheDocument()
    expect(screen.getByText(copy.completionDone)).toBeInTheDocument()
    expect(screen.getByText(accountDeletionTimeText(COMPLETED_AT))).toBeInTheDocument()
    expect(screen.getByText(accountDeletionContent.progress.stateCompleted)).toBeInTheDocument()
    expect(screen.queryByText(copy.overdueNotice)).toBeNull()
    // 전체 완료면 화면 수명 한계 안내는 필요 없다.
    expect(screen.queryByText(copy.limitNotice)).toBeNull()
  })

  it('진행 화면에서 탭이 다시 보일 때 삭제가 끝났으면 만료로 보내지 않고 완료 화면을 그린다', async () => {
    window.history.pushState({}, '', SETTINGS_PATH)
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
    stubAccountDeletionState(() =>
      Promise.resolve(
        jsonResponse({
          available: true,
          policy: POLICY,
          request: {
            requestId: 'del_done',
            requestedAt: REQUESTED_AT,
            state: 'DELETION_IN_PROGRESS',
            policyVersion: ACCOUNT_DELETION_POLICY_VERSION,
            progress: { status: 'IN_PROGRESS', failedSteps: [] },
          },
        }),
      ),
    )
    render(<SettingsScreen />)
    expect(
      await screen.findByText(accountDeletionContent.progress.state, undefined, {
        timeout: FIRST_MOUNT_TIMEOUT_MS,
      }),
    ).toBeInTheDocument()

    // 그 사이 삭제가 끝났다 — 세션 확인은 완료 확인 세션(미인증 + 완료 표지)으로 답한다.
    completionSession()
    stubAccountDeletionState(completionState())
    Object.defineProperty(document, 'visibilityState', { configurable: true, value: 'visible' })
    document.dispatchEvent(new Event('visibilitychange'))

    await findCompletionHeading(accountDeletionContent.completion.title)
    expect(vi.mocked(replaceLocation)).not.toHaveBeenCalled()
  })

  it('백업 사본 상태를 서버가 읽지 못했으면 확인하지 못함이라 말한다', async () => {
    window.history.pushState({}, '', SETTINGS_PATH)
    stubAccountDeletionState(
      completionState({ backupDestruction: null, backupDeadline: null, requestedAt: null }, null),
    )
    render(<SettingsScreen />)

    await findCompletionHeading(accountDeletionContent.completion.title)
    expect(
      await screen.findAllByText(accountDeletionContent.completion.backupUnknown),
    ).toHaveLength(2)
    // 연락처가 없으면 지원 경로를 지어내지 않는다.
    expect(screen.queryByRole('link', { name: /@/ })).toBeNull()
  })

  it('진행 중 화면에도 설정된 지원 연락처를 둔다(deferred 170)', async () => {
    window.history.pushState({}, '', SETTINGS_PATH)
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
    stubAccountDeletionState(() =>
      Promise.resolve(
        jsonResponse({
          available: true,
          policy: POLICY,
          request: {
            requestId: 'del_abc',
            requestedAt: REQUESTED_AT,
            state: 'DELETION_IN_PROGRESS',
            policyVersion: ACCOUNT_DELETION_POLICY_VERSION,
            progress: { status: 'RETRY_NEEDED', failedSteps: ['SYNC'] },
          },
          supportContact: SUPPORT,
        }),
      ),
    )
    render(<SettingsScreen />)

    await findCompletionHeading(accountDeletionContent.progress.title)
    expect(
      await screen.findByRole(
        'button',
        { name: accountDeletionContent.progress.retryErasure },
        { timeout: FIRST_MOUNT_TIMEOUT_MS },
      ),
    ).toBeInTheDocument()
    expect(screen.getByRole('link', { name: SUPPORT })).toHaveAttribute('href', `mailto:${SUPPORT}`)
    // 진행 화면도 요청 ID를 보이고, 문의 문장이 그 값을 알려 달라고 말한다.
    expect(screen.getByText('del_abc')).toBeInTheDocument()
    expect(screen.getByText(accountDeletionContent.progress.supportBody)).toBeInTheDocument()
    expect(accountDeletionContent.progress.supportBody).toContain('요청 ID')
  })

  it('완료 세션은 완료 조회를 읽지 못해도 진행 중이라 말하지 않는다', async () => {
    window.history.pushState({}, '', SETTINGS_PATH)
    stubAccountDeletionState(() => Promise.resolve(jsonResponse({ code: 'X' }, 500)))
    render(<SettingsScreen />)

    const copy = accountDeletionContent.completion
    await findCompletionHeading(copy.title)
    expect(
      await within(screen.getByRole('status')).findByText(copy.unavailable),
    ).toBeInTheDocument()
    expect(screen.queryByText(accountDeletionContent.progress.title)).toBeNull()
    expect(screen.queryByText(accountDeletionContent.progress.unavailable)).toBeNull()
    expect(screen.getByText(copy.body)).toBeInTheDocument()
    expect(screen.getByText(copy.signOutHint)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: copy.retry })).toBeInTheDocument()
    expect(vi.mocked(replaceLocation)).not.toHaveBeenCalled()
  })

  it('다시 확인하는 동안에도 제목이 되돌아가지 않는다', async () => {
    window.history.pushState({}, '', SETTINGS_PATH)
    stubAccountDeletionState(
      completionState({
        backupDestruction: 'DESTROYED',
        backupDestroyedAt: DESTROYED_AT,
        deletionCompleted: true,
        deletionCompletedAt: COMPLETED_AT,
      }),
    )
    render(<SettingsScreen />)
    const copy = accountDeletionContent.completion
    await findCompletionHeading(copy.titleCompleted)

    // 다시 확인이 응답하지 않는 동안(확인 중)과 실패한 뒤에도 전체 완료 제목이다.
    let answer: (response: Response) => void = () => undefined
    stubAccountDeletionState(
      () =>
        new Promise<Response>((resolve) => {
          answer = resolve
        }),
    )
    fireEvent.click(screen.getByRole('button', { name: copy.retry }))
    expect(await within(screen.getByRole('status')).findByText(copy.checking)).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: copy.titleCompleted })).toBeInTheDocument()
    expect(screen.queryByText(accountDeletionContent.progress.title)).toBeNull()

    answer(jsonResponse({ code: 'X' }, 500))
    expect(
      await within(screen.getByRole('status')).findByText(copy.unavailable),
    ).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: copy.titleCompleted })).toBeInTheDocument()
  })

  it('다시 시도 중 삭제가 끝났으면 만료로 보내지 않고 완료 화면을 그린다', async () => {
    window.history.pushState({}, '', SETTINGS_PATH)
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
    stubAccountDeletionState(retryNeeded)
    render(<SettingsScreen />)
    const retryButton = await screen.findByRole(
      'button',
      { name: accountDeletionContent.progress.retryErasure },
      { timeout: FIRST_MOUNT_TIMEOUT_MS },
    )

    // 그 사이 삭제가 끝나 이 세션은 완료 확인 세션이 됐다 — 다시 시도는 401, 다시 읽은 조회는 완료다.
    stubAccountDeletionRetry(() => Promise.resolve(jsonResponse({ code: 'UNAUTHENTICATED' }, 401)))
    stubAccountDeletionState(completionState())
    fireEvent.click(retryButton)

    await findCompletionHeading(accountDeletionContent.completion.title)
    expect(screen.getByText('del_done')).toBeInTheDocument()
    expect(vi.mocked(replaceLocation)).not.toHaveBeenCalled()
  })

  it('다시 시도가 401이고 다시 읽어도 완료가 아니면 만료로 보낸다', async () => {
    window.history.pushState({}, '', SETTINGS_PATH)
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
    stubAccountDeletionState(retryNeeded)
    render(<SettingsScreen />)
    const retryButton = await screen.findByRole(
      'button',
      { name: accountDeletionContent.progress.retryErasure },
      { timeout: FIRST_MOUNT_TIMEOUT_MS },
    )

    stubAccountDeletionRetry(() => Promise.resolve(jsonResponse({ code: 'UNAUTHENTICATED' }, 401)))
    stubAccountDeletionState(() => Promise.resolve(jsonResponse({ code: 'UNAUTHENTICATED' }, 401)))
    fireEvent.click(retryButton)

    await vi.waitFor(() => {
      expect(vi.mocked(replaceLocation)).toHaveBeenCalledWith(noticePath('expired'))
    })
  })

  it('완료 조회의 모양이 틀리면 완료를 믿지 않는다', () => {
    expect(parseDeletionCompletion(completionBody())).not.toBeNull()
    // 전체 완료와 그 시각은 한 쌍이다.
    expect(parseDeletionCompletion(completionBody({ deletionCompleted: true }))).toBeNull()
    expect(
      parseDeletionCompletion(completionBody({ deletionCompletedAt: COMPLETED_AT })),
    ).toBeNull()
    expect(parseDeletionCompletion(completionBody({ backupDestruction: 'GONE' }))).toBeNull()
    expect(parseDeletionCompletion(completionBody({ operationalErasedAt: null }))).toBeNull()
    expect(
      parseDeletionState({ available: true, policy: POLICY, request: null, completion: {} }),
    ).toBeNull()
    expect(
      parseDeletionState({ available: true, policy: POLICY, request: null, supportContact: 7 })
        ?.supportContact,
    ).toBeNull()
  })

  it('백업 사본 파기 상태가 공유 표와 같고 문장이 서로 다르다', () => {
    expect([...BACKUP_DESTRUCTION_STATUSES]).toEqual(
      accountDeletionPolicy.backupDestructionStatuses,
    )
    const sentences = new Set([...BACKUP_DESTRUCTION_STATUSES, null].map(backupDestructionText))
    expect(sentences.size).toBe(4)
  })

  it('상태 줄은 백업 사본 파기 상태마다 다르고 전체 완료는 하나다', () => {
    const lines = new Set(
      [...BACKUP_DESTRUCTION_STATUSES, null].map((status) => completionStateText(false, status)),
    )
    expect(lines.size).toBe(4)
    expect(completionStateText(true, 'DESTROYED')).toBe(
      accountDeletionContent.progress.stateCompleted,
    )
  })

  it('지원 연락처는 이메일 주소 모양일 때만 받는다(서비스 공지·문의와 같은 판정)', () => {
    const base = { available: true, policy: POLICY, request: null }
    expect(parseDeletionState({ ...base, supportContact: SUPPORT })?.supportContact).toBe(SUPPORT)
    for (const bad of ['javascript:alert(1)', 'a b@c.test', 'x@y', 'support@bokrilog.test?cc=x']) {
      expect(parseDeletionState({ ...base, supportContact: bad })?.supportContact).toBeNull()
    }
  })

  it('완료 안내 카피에 금지 표현이 없다', () => {
    const strings = [
      ...Object.values(accountDeletionContent.completion),
      accountDeletionContent.progress.stateCompleted,
      accountDeletionContent.progress.supportBody,
      accountDeletionRefusalMessage('ACCOUNT_DELETION_COMPLETED'),
      authContent.notices.deleted,
    ]
    for (const text of strings) {
      for (const word of FORBIDDEN_COPY_WORDS) {
        expect(text).not.toContain(word)
      }
    }
    expect(accountDeletionRefusalMessage('ACCOUNT_DELETION_COMPLETED')).not.toBe(
      accountDeletionRefusalMessage(null),
    )
  })

  it('남기는 것에 재가입 차단 기록과 그 기한이 있다', () => {
    const line = accountDeletionContent.kept.find((kept) => kept.startsWith('재가입 차단 기록'))
    expect(line).toBeDefined()
    // 기한은 전체 삭제 완료다(D-4) — 무기한으로 남긴다고 말하지 않는다.
    expect(line).toContain('삭제가 모두 끝나면 이 기록도 지웁니다')
    // 완료 화면의 한계 문장·목표일 설명도 백업 파기 기준점을 운영 데이터 삭제를 마친 다음 날로 말하고 일 수는 공유 표 값이다(D-1).
    const promise = `운영 데이터 삭제를 마친 다음 날부터 최대 ${String(accountDeletionPolicy.backupDestroyDays)}일`
    expect(accountDeletionContent.completion.limitNotice).toContain(promise)
    expect(accountDeletionContent.progress.backupTargetNote).toContain(promise)
    // 범위 없는 복원 약속을 남기지 않는다 — 고지 문장과 같은 조건(서비스 밖 보관소)을 말한다.
    expect(accountDeletionContent.completion.overdueNotice).toContain(
      '서비스 밖 보관소에 남은 뒤에는',
    )
    for (const text of [
      accountDeletionContent.progress.backupTargetNote,
      accountDeletionContent.completion.overdueNotice,
      accountDeletionContent.completion.limitNotice,
    ]) {
      for (const word of FORBIDDEN_COPY_WORDS) {
        expect(text).not.toContain(word)
      }
    }
  })
})

describe('삭제한 로그인 수단 표지', () => {
  it('진입 화면은 deleted 표지로 재가입이 막혔다는 사실을 말한다', () => {
    expect(noticeKindOf('?auth=deleted')).toBe('deleted')
    render(<EntryScreen notice={noticeKindOf('?auth=deleted')} />)
    expect(screen.getByRole('status').textContent).toBe(authContent.notices.deleted)
    expect(authContent.notices.deleted).not.toBe(authContent.notices.failed)
    expect(screen.getAllByRole('status')).toHaveLength(1)
  })
})
