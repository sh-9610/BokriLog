import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { CSRF_HEADER } from '../content/auth'
import {
  incidentOptionLabel,
  operatorContent,
  operatorCopyStrings,
  operatorRefusalMessage,
  PUBLISH_NOTICE_COMMAND,
} from '../content/operator'
import {
  getObservedAllowedRequests,
  getObservedStorageEntries,
  stubOperatorAudit,
  stubOperatorNotices,
  stubOperatorRestrictionCommand,
  stubOperatorRestrictions,
  stubSessionRequest,
} from '../test/setup'
import { OperatorScreen } from './OperatorScreen'

vi.mock('../session/navigation', () => ({ navigateTo: vi.fn(), replaceLocation: vi.fn() }))

/**
 * Story 3.5: 운영자 화면의 서비스 공지 영역. 사건 선택·상태·덮을 활성 제한·메시지·다음 안내 시각 → 준비 → 확인
 * 대화상자(서버가 계산한 영향 미리보기, 문구 재입력, 취소 기본 포커스) → 확정·취소. 사건 이력은 읽기 전용이다.
 */

const copy = operatorContent.notice

const dialogCopy = operatorContent.noticeDialog

const CSRF_TOKEN = 'operator-notice-csrf'

const AD_RESTRICTION = '5b1d7c3e-2f4a-4b6c-8d0e-1a2b3c4d5e6f'

const INCIDENT = '9a8b7c6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d'

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

function restriction(): Record<string, unknown> {
  return {
    restrictionId: AD_RESTRICTION,
    feature: 'ADVERTISING',
    scope: 'GLOBAL',
    target: 'ALL',
    targetErased: false,
    effectiveFrom: '2026-10-10T00:00:00Z',
    reason: '광고 오류',
    actorDigest: 'a'.repeat(64),
    origin: 'OPERATOR',
    linkedSignals: 0,
    requiresVerification: false,
    reinstated: false,
  }
}

/** 이미 복구(해제)돼 활성 목록에 없는 제한. */
const CLOSED_RESTRICTION = '0f1e2d3c-4b5a-4968-8776-65544332211a'

function incident(
  closed = false,
  covers: readonly string[] = [AD_RESTRICTION],
): Record<string, unknown> {
  return {
    incidentId: INCIDENT,
    startedAt: '2026-10-10T00:00:00Z',
    closed,
    versions: [
      {
        version: 1,
        status: 'INVESTIGATING',
        affectedFeatures: ['ADVERTISING'],
        continuingFeatures: ['NEW_CONNECTION', 'SYNC', 'CALCULATION_DISPLAY'],
        message: '광고 오류 조사 중',
        nextUpdateAt: '2026-10-10T03:00:00Z',
        covers,
        operationId: 'op_notice1',
        createdAt: '2026-10-10T00:00:00Z',
      },
    ],
  }
}

function list(configured = true, items: Record<string, unknown>[] = []): Promise<Response> {
  return Promise.resolve(json({ supportContactConfigured: configured, items }))
}

async function open(): Promise<HTMLElement> {
  stubSessionRequest(() =>
    Promise.resolve(json({ authenticated: true, destination: 'operator', csrfToken: CSRF_TOKEN })),
  )
  stubOperatorAudit(() => Promise.resolve(json({ items: [], nextCursor: null })))
  stubOperatorRestrictions(() => Promise.resolve(json({ items: [restriction()] })))
  render(<OperatorScreen />)
  await screen.findByRole('heading', { level: 2, name: copy.heading })
  return screen.getByRole('region', { name: copy.heading })
}

function startButton(): HTMLElement {
  return screen.getByRole('button', { name: copy.start })
}

beforeEach(() => {
  window.history.pushState({}, '', '/operator')
})

describe('OperatorScreen 서비스 공지', () => {
  it('지원 연락처가 없으면 그 사실을 말하고 준비를 막는다', async () => {
    stubOperatorNotices(() => list(false))
    const section = await open()
    expect(await within(section).findByText(copy.contactMissing)).toBeInTheDocument()
    fireEvent.click(within(section).getByRole('checkbox'))
    fireEvent.change(within(section).getByLabelText(copy.messageLabel), {
      target: { value: '광고 오류 조사 중' },
    })
    fireEvent.change(within(section).getByLabelText(copy.nextUpdateLabel), {
      target: { value: '2026-10-11T10:00' },
    })
    expect(startButton()).toHaveAttribute('aria-disabled', 'true')
    fireEvent.click(startButton())
    expect(getObservedAllowedRequests().filter((request) => request.startsWith('POST'))).toEqual([])
  })

  it('새 사건을 준비하고 서버 미리보기를 확인한 뒤 문구를 다시 입력해 발행한다', async () => {
    let listed = 0
    stubOperatorNotices(() => {
      listed += 1
      return list(true, listed > 1 ? [incident()] : [])
    })
    const bodies: unknown[] = []
    const keys: (string | null)[] = []
    stubOperatorRestrictionCommand(PUBLISH_NOTICE_COMMAND, 'prepare', (init) => {
      bodies.push(JSON.parse(init?.body as string))
      const headers = new Headers(init?.headers)
      keys.push(headers.get('Idempotency-Key'))
      expect(headers.get(CSRF_HEADER)).toBe(CSRF_TOKEN)
      return Promise.resolve(
        json({
          operationId: 'op_notice_prepared',
          command: PUBLISH_NOTICE_COMMAND,
          scope: 'NOTICE:INVESTIGATING',
          target: 'NEW',
          reason: '광고 오류 조사 중',
          confirmationPhrase: '공지 발행 새 사건',
          policyVersion: 'OPERATOR-COMMAND-1.3',
          requestedAt: '2026-10-10T00:00:00Z',
          impact: {
            incident: 'NEW',
            version: 1,
            status: 'INVESTIGATING',
            affectedFeatures: ['ADVERTISING'],
            continuingFeatures: ['NEW_CONNECTION', 'SYNC', 'CALCULATION_DISPLAY'],
            covers: [AD_RESTRICTION],
            nextUpdateAt: '2026-10-11T01:00:00Z',
          },
        }),
      )
    })
    stubOperatorRestrictionCommand(PUBLISH_NOTICE_COMMAND, 'confirm', (init) => {
      const headers = new Headers(init?.headers)
      keys.push(headers.get('Idempotency-Key'))
      expect(JSON.parse(init?.body as string)).toEqual({
        operationId: 'op_notice_prepared',
        confirmation: '공지 발행 새 사건',
      })
      return Promise.resolve(
        json({
          operationId: 'op_notice_prepared',
          outcome: 'COMPLETED',
          command: PUBLISH_NOTICE_COMMAND,
          incidentId: INCIDENT,
          version: 1,
          status: 'INVESTIGATING',
          publishedAt: '2026-10-10T00:01:00Z',
        }),
      )
    })
    const section = await open()
    await within(section).findByText(copy.history.empty)

    // 활성 제한을 고르고 메시지·다음 안내 시각을 채운다. 새 사건이면 상태 선택이 없다(조사 중).
    expect(within(section).queryByLabelText(copy.statusLabel)).not.toBeInTheDocument()
    fireEvent.click(within(section).getByRole('checkbox', { name: /광고 · 전역/ }))
    fireEvent.change(within(section).getByLabelText(copy.messageLabel), {
      target: { value: '광고 오류 조사 중' },
    })
    fireEvent.change(within(section).getByLabelText(copy.nextUpdateLabel), {
      target: { value: '2026-10-11T10:00' },
    })
    fireEvent.click(startButton())

    const dialog = await screen.findByRole('dialog', { name: dialogCopy.title })
    expect(within(dialog).getByRole('button', { name: dialogCopy.cancel })).toHaveFocus()
    expect(dialog.textContent).toContain(`${dialogCopy.affected}: 광고`)
    // 미리보기는 사용자 화면의 공지와 같은 문장이다.
    expect(dialog.textContent).toContain(
      `${dialogCopy.continuing}: 새 거래소 연결, 동기화와 새 계산 반영, 계산 결과 표시`,
    )
    expect(dialog.textContent).toContain(dialogCopy.covers(1))
    expect(dialog.textContent).toContain(`${dialogCopy.incident}: ${dialogCopy.newIncident}`)
    expect(bodies).toEqual([
      {
        incident: 'NEW',
        status: 'INVESTIGATING',
        covers: [AD_RESTRICTION],
        message: '광고 오류 조사 중',
        // 입력은 서울 시각(KST)으로 읽는다 — 브라우저 시간대와 무관하다.
        nextUpdateAt: '2026-10-11T01:00:00.000Z',
      },
    ])
    // 문구가 같아질 때까지 실행은 막혀 있다.
    const confirm = within(dialog).getByRole('button', { name: dialogCopy.confirm })
    expect(confirm).toHaveAttribute('aria-disabled', 'true')
    fireEvent.change(within(dialog).getByRole('textbox'), {
      target: { value: '공지 발행 새 사건' },
    })
    expect(confirm).toHaveAttribute('aria-disabled', 'false')
    fireEvent.click(confirm)

    const result = await screen.findByRole('heading', { level: 3, name: copy.result.heading })
    await waitFor(() => {
      expect(result).toHaveFocus()
    })
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(screen.getByRole('status').textContent).toBe(
      operatorContent.status.noticeCompleted(1, '조사 중'),
    )
    // 준비와 확정이 같은 멱등 키다. 발행 뒤 사건 목록을 다시 읽는다.
    expect(keys).toHaveLength(2)
    expect(keys[0]).toBe(keys[1])
    const history = await within(section).findByRole('list', { name: copy.history.heading })
    expect(within(history).getByText(INCIDENT)).toBeInTheDocument()
    expect(within(history).getByText('v1 조사 중')).toBeInTheDocument()
    expect(getObservedStorageEntries()).toEqual([])
    expect(new URL(window.location.href).search).toBe('')
  })

  it('범위 변경은 직전 버전이 덮던 제한 가운데 활성인 것만 보내고 번호 충돌은 문장으로 말한다', async () => {
    stubOperatorNotices(() => list(true, [incident(false, [AD_RESTRICTION, CLOSED_RESTRICTION])]))
    const bodies: unknown[] = []
    stubOperatorRestrictionCommand(PUBLISH_NOTICE_COMMAND, 'prepare', (init) => {
      bodies.push(JSON.parse(init?.body as string))
      return Promise.resolve(
        json({ code: 'NOTICE_VERSION_CONFLICT', message: '충돌', correlationId: 'c' }, 409),
      )
    })
    const section = await open()
    const select = await within(section).findByLabelText(copy.incidentLabel)
    fireEvent.change(select, { target: { value: INCIDENT } })
    expect(
      within(section).getByRole('option', {
        name: incidentOptionLabel('2026-10-10T00:00:00Z', 1, 'INVESTIGATING', INCIDENT),
      }),
    ).toBeInTheDocument()
    // 최신 버전이 덮던 활성 제한을 미리 고른다. 닫힌 제한은 목록에 없으니 보내지 않는다.
    expect(within(section).getByRole('checkbox', { name: /광고 · 전역/ })).toBeChecked()
    expect(within(section).getAllByRole('checkbox')).toHaveLength(1)
    fireEvent.change(within(section).getByLabelText(copy.messageLabel), {
      target: { value: '표시 영향도 확인' },
    })
    fireEvent.change(within(section).getByLabelText(copy.nextUpdateLabel), {
      target: { value: '2026-10-11T10:00' },
    })
    fireEvent.click(startButton())

    await waitFor(() => {
      expect(screen.getByRole('status').textContent).toBe(
        operatorRefusalMessage('NOTICE_VERSION_CONFLICT', PUBLISH_NOTICE_COMMAND),
      )
    })
    expect(bodies).toEqual([
      {
        incident: INCIDENT,
        status: 'SCOPE_CHANGED',
        covers: [AD_RESTRICTION],
        message: '표시 영향도 확인',
        nextUpdateAt: '2026-10-11T01:00:00.000Z',
      },
    ])
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  it('복구 공지는 덮는 제한을 고르지 않고, 아직 효력 중인 제한이 있으면 미리 말하고 막는다', async () => {
    stubOperatorNotices(() => list(true, [incident()]))
    const section = await open()
    fireEvent.change(await within(section).findByLabelText(copy.incidentLabel), {
      target: { value: INCIDENT },
    })
    fireEvent.change(within(section).getByLabelText(copy.statusLabel), {
      target: { value: 'RECOVERED' },
    })
    expect(within(section).queryByLabelText(copy.nextUpdateLabel)).not.toBeInTheDocument()
    expect(within(section).queryByRole('checkbox')).not.toBeInTheDocument()
    expect(within(section).getByText(copy.recoveredStillOpen(1))).toBeInTheDocument()
    fireEvent.change(within(section).getByLabelText(copy.messageLabel), {
      target: { value: '원인 수정 배포 완료' },
    })
    expect(startButton()).toHaveAttribute('aria-disabled', 'true')
    fireEvent.click(startButton())
    expect(getObservedAllowedRequests().filter((request) => request.startsWith('POST'))).toEqual([])
  })

  it('덮던 제한이 모두 닫힌 사건의 복구 공지는 덮는 제한을 비워 보내고 대상 거절은 복구 문장이다', async () => {
    stubOperatorNotices(() => list(true, [incident(false, [CLOSED_RESTRICTION])]))
    const bodies: unknown[] = []
    stubOperatorRestrictionCommand(PUBLISH_NOTICE_COMMAND, 'prepare', (init) => {
      bodies.push(JSON.parse(init?.body as string))
      return Promise.resolve(
        json({ code: 'TARGET_INVALID', message: '대상', correlationId: 'c' }, 422),
      )
    })
    const section = await open()
    fireEvent.change(await within(section).findByLabelText(copy.incidentLabel), {
      target: { value: INCIDENT },
    })
    fireEvent.change(within(section).getByLabelText(copy.statusLabel), {
      target: { value: 'RECOVERED' },
    })
    expect(within(section).queryByText(copy.recoveredStillOpen(1))).not.toBeInTheDocument()
    fireEvent.change(within(section).getByLabelText(copy.messageLabel), {
      target: { value: '원인 수정 배포 완료' },
    })
    fireEvent.click(startButton())
    await waitFor(() => {
      expect(screen.getByRole('status').textContent).toBe(
        operatorRefusalMessage('TARGET_INVALID', PUBLISH_NOTICE_COMMAND, 'RECOVERED'),
      )
    })
    expect(bodies).toEqual([
      { incident: INCIDENT, status: 'RECOVERED', covers: [], message: '원인 수정 배포 완료' },
    ])
  })

  it('메시지에 화면 카피 금지 표현이 있으면 그 사실을 말하고 준비를 막는다', async () => {
    stubOperatorNotices(() => list(true))
    const section = await open()
    fireEvent.click(within(section).getByRole('checkbox'))
    const message = within(section).getByLabelText(copy.messageLabel)
    fireEvent.change(message, { target: { value: '실시간 복구 중' } })
    fireEvent.change(within(section).getByLabelText(copy.nextUpdateLabel), {
      target: { value: '2026-10-11T10:00' },
    })
    expect(message).toHaveAttribute('aria-invalid', 'true')
    expect(within(section).getByText(copy.messageForbidden)).toBeInTheDocument()
    expect(startButton()).toHaveAttribute('aria-disabled', 'true')
  })

  it('닫힌 사건은 선택지에 없고 이력에는 닫힘으로 보인다', async () => {
    stubOperatorNotices(() => list(true, [incident(true)]))
    const section = await open()
    const history = await within(section).findByRole('list', { name: copy.history.heading })
    expect(within(history).getByText(copy.history.closed)).toBeInTheDocument()
    expect(within(section).getAllByRole('option')).toHaveLength(1)
    // 이력은 읽기 전용이다 — 사건 행에 조작이 없다.
    expect(within(history).queryByRole('button')).not.toBeInTheDocument()
    for (const text of operatorCopyStrings) {
      expect(text).not.toMatch(/실시간|정확|보장|축하|손실/)
    }
  })
})
