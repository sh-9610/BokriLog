import { useEffect, useLayoutEffect, useRef, useState } from 'react'

import {
  NOTICE_FEATURES,
  NOTICE_STATUSES,
  SERVICE_NOTICES_PATH,
  type NoticeFeature,
  type NoticeStatus,
} from '../content/serviceNotice'
import { objectOf } from './http'
import { isInstant } from './instant'
import { SESSION_REQUEST_TIMEOUT_MS } from './useSessionState'

/** 공지 하나(Story 3.5). 사용자 식별자·금융값이 없다. */
export interface NoticeItem {
  readonly kind: 'NOTICE'
  readonly incidentId: string
  readonly version: number
  readonly status: NoticeStatus
  readonly startedAt: string
  readonly affectedFeatures: readonly NoticeFeature[]
  readonly continuingFeatures: readonly NoticeFeature[]
  readonly message: string
  /** 진행 중인 공지의 다음 안내 시각. 복구 공지는 `null`이다. */
  readonly nextUpdateAt: string | null
  /** 다음 안내 시각이 서버 판정 시각에 이미 지났다. 화면은 다음 안내가 늦어지고 있다고 말한다. */
  readonly overdue: boolean
  /** 복구 공지의 복구 시각. 진행 중인 공지는 `null`이다. */
  readonly recoveredAt: string | null
  /** 지원 연락처 이메일. 설정이 없으면 `null`이고 화면은 링크를 두지 않는다. */
  readonly supportContact: string | null
}

/** 효력 중인 제한을 덮는 공지가 없다. 무엇이 멈췄는지만 말한다. */
export interface RestrictedWithoutNoticeItem {
  readonly kind: 'RESTRICTED_WITHOUT_NOTICE'
  readonly affectedFeatures: readonly NoticeFeature[]
  readonly continuingFeatures: readonly NoticeFeature[]
  readonly supportContact: string | null
}

export type ServiceNoticeItem = NoticeItem | RestrictedWithoutNoticeItem

/** 검증을 지난 공지 응답. `expiresAt`까지만 믿는다. */
export interface ServiceNotices {
  readonly asOf: string
  readonly expiresAt: string
  readonly items: readonly ServiceNoticeItem[]
}

/**
 * 화면이 보는 공지 상태. 확인 중에는 아무것도 주장하지 않고, 검증된 응답(방금 받았거나 만료 전에 유지한 것)이 있으면
 * 그것을, 없으면 "확인하지 못했다" 한 문장을 보인다.
 */
export type ServiceNoticeView =
  | { readonly kind: 'checking' }
  | { readonly kind: 'ready'; readonly notices: ServiceNotices }
  | { readonly kind: 'unavailable' }

/**
 * 다시 읽기 간격의 바닥. 다시 읽기는 남은 신뢰 시간의 절반에 한다 — 그 읽기가 실패해도 만료 전이면 마지막 응답을
 * 유지하고, 다음 읽기는 다시 남은 시간의 절반이다. 이 바닥 아래로는 줄이지 않는다(요청을 몰아치지 않는다).
 */
export const NOTICE_REFRESH_FLOOR_MS = 15_000

/** 확인하지 못한 상태의 다시 읽기 간격. */
export const NOTICE_RETRY_MS = 30_000

/** 지원 연락처의 모양. `mailto:`에 그대로 싣는 값이라 공백·꺾쇠·따옴표·쿼리가 없어야 한다. */
const CONTACT = /^[A-Za-z0-9._%+-]{1,64}@[A-Za-z0-9-]+(\.[A-Za-z0-9-]+)*\.[A-Za-z]{2,}$/

function featuresOf(value: unknown): readonly NoticeFeature[] | null {
  if (!Array.isArray(value)) return null
  const features: NoticeFeature[] = []
  for (const item of value) {
    const known = NOTICE_FEATURES.find((feature) => feature === item)
    if (known === undefined || features.includes(known)) return null
    features.push(known)
  }
  return features
}

function contactOf(value: unknown): string | null | undefined {
  if (value === undefined || value === null) return null
  return typeof value === 'string' && value.length <= 254 && CONTACT.test(value) ? value : undefined
}

function itemOf(value: unknown): ServiceNoticeItem | null {
  const body = objectOf(value)
  if (body === null) return null
  const affected = featuresOf(body.affectedFeatures)
  const continuing = featuresOf(body.continuingFeatures)
  const supportContact = contactOf(body.supportContact)
  if (
    affected === null ||
    affected.length === 0 ||
    continuing === null ||
    supportContact === undefined
  ) {
    return null
  }
  if (body.kind === 'RESTRICTED_WITHOUT_NOTICE') {
    return {
      kind: 'RESTRICTED_WITHOUT_NOTICE',
      affectedFeatures: affected,
      continuingFeatures: continuing,
      supportContact,
    }
  }
  if (body.kind !== 'NOTICE') return null
  const status = NOTICE_STATUSES.find((known) => known === body.status)
  if (
    status === undefined ||
    typeof body.incidentId !== 'string' ||
    body.incidentId === '' ||
    typeof body.version !== 'number' ||
    !Number.isInteger(body.version) ||
    body.version < 1 ||
    !isInstant(body.startedAt) ||
    typeof body.message !== 'string' ||
    body.message.trim() === ''
  ) {
    return null
  }
  const recovered = status === 'RECOVERED'
  const next = body.nextUpdateAt
  const recoveredAt = body.recoveredAt
  const overdue = body.overdue
  // 늦어짐은 진행 중인 공지에만 있다. 복구 공지가 늦어짐을 말하면 모르는 모양이다.
  if ((overdue !== undefined && typeof overdue !== 'boolean') || (recovered && overdue === true)) {
    return null
  }
  // 상태별 다음 시각이 계약이다: 진행 중이면 다음 안내 시각만, 복구면 복구 시각만.
  if (
    recovered ? !isInstant(recoveredAt) || next != null : !isInstant(next) || recoveredAt != null
  ) {
    return null
  }
  return {
    kind: 'NOTICE',
    incidentId: body.incidentId,
    version: body.version,
    status,
    startedAt: body.startedAt,
    affectedFeatures: affected,
    continuingFeatures: continuing,
    message: body.message,
    nextUpdateAt: recovered ? null : (next as string),
    overdue: overdue === true,
    recoveredAt: recovered ? (recoveredAt as string) : null,
    supportContact,
  }
}

/**
 * 공지 응답을 검증한다. 하나라도 모르는 모양이면 묶음 전체를 믿지 않는다(`null`) — 모르는 항목을 빼고 나머지를 보이면
 * 멈춘 기능을 숨길 수 있다.
 */
export function parseServiceNotices(payload: unknown): ServiceNotices | null {
  const body = objectOf(payload)
  if (
    body === null ||
    !isInstant(body.asOf) ||
    !isInstant(body.expiresAt) ||
    !Array.isArray(body.items)
  ) {
    return null
  }
  const items: ServiceNoticeItem[] = []
  for (const value of body.items as unknown[]) {
    const item = itemOf(value)
    if (item === null) return null
    items.push(item)
  }
  return { asOf: body.asOf, expiresAt: body.expiresAt, items }
}

/** 검증한 응답과 그것을 믿는 마지막 순간(브라우저 시계, 받은 시각 + 서버가 정한 신뢰 기간). */
interface Accepted {
  readonly notices: ServiceNotices
  readonly deadline: number
}

/**
 * 받은 응답의 신뢰 기한. 서버의 절대 시각을 브라우저 시계와 비교하지 않는다 — 두 시계가 어긋나도 같은 기간을 믿도록 서버가
 * 정한 상대 기간(`expiresAt − asOf`)을 받은 순간에 더한다.
 */
function deadlineOf(notices: ServiceNotices, receivedAt: number): number {
  const ttl = Date.parse(notices.expiresAt) - Date.parse(notices.asOf)
  return receivedAt + Math.max(ttl, 0)
}

/**
 * 인증 사용자의 서비스 공지(Story 3.5).
 *
 * 세션이 준비되면 한 번 읽고, 검증한 응답의 신뢰 기한(서버가 정한 `expiresAt − asOf`를 받은 순간에 더한 것)까지 남은
 * 시간의 절반마다 조용히 다시 읽는다 — 재시도 버튼이 없다. 조회가 실패하거나 모르는 모양이면 마지막으로 검증된 응답이
 * 기한 전일 때만 그것을 유지하고, 아니면 `unavailable`이다. 기한이 지난 응답은 렌더 시점에도 보이지 않고, 기한에 맞춘
 * 타이머가 그 응답을 "확인하지 못했다"로 접는다. 401·403·404는 메모리의 응답을 지우고 다시 묻지 않는다. 늦은 응답은
 * 버린다. 값은 메모리에만 있다 — 브라우저 저장소에 쓰지 않는다.
 *
 * 화면의 live region을 늘리지 않는다. 공지 영역은 정적 콘텐츠이고 바뀌어도 알리지 않는다.
 */
export function useServiceNotices(ready: boolean): ServiceNoticeView {
  const [state, setState] = useState<
    | { readonly kind: 'checking' }
    | { readonly kind: 'unavailable' }
    | ({ readonly kind: 'ready' } & Accepted)
  >({ kind: 'checking' })
  const [serial, setSerial] = useState(0)
  const [wasReady, setWasReady] = useState(ready)
  const sequence = useRef(0)
  const accepted = useRef<Accepted | null>(null)
  /** 401·403·404를 받았다. 세션이 끝났거나 거절됐으니 다시 묻지 않는다(세션 게이트가 이동을 맡는다). */
  const denied = useRef(false)

  // 세션이 준비되지 않으면 공지를 화면과 메모리에서 함께 내린다(렌더 중 상태 조정 — React의 문서화된 관용).
  if (ready !== wasReady) {
    setWasReady(ready)
    if (!ready) setState({ kind: 'checking' })
  }

  // 요청 시작은 passive effect다. 커밋이 보여 주는 것이 아니고 답은 어차피 다음 task에 온다.
  useEffect(() => {
    if (!ready) {
      accepted.current = null
      denied.current = false
      return
    }
    sequence.current += 1
    const mine = sequence.current
    const controller = new AbortController()
    const deadline = window.setTimeout(() => {
      controller.abort()
    }, SESSION_REQUEST_TIMEOUT_MS)
    const failed = (): void => {
      const kept = accepted.current
      if (kept !== null && Date.now() < kept.deadline) {
        setState({ kind: 'ready', ...kept })
        return
      }
      accepted.current = null
      setState({ kind: 'unavailable' })
    }
    const read = async (): Promise<void> => {
      try {
        const response = await fetch(SERVICE_NOTICES_PATH, {
          credentials: 'same-origin',
          cache: 'no-store',
          headers: { Accept: 'application/json' },
          signal: controller.signal,
        })
        const payload: unknown = response.status === 200 ? await response.json() : null
        if (sequence.current !== mine) return
        if (response.status === 401 || response.status === 403 || response.status === 404) {
          // 세션이 끝났거나 거절됐다(삭제가 시작된 계정은 404). 공지를 메모리에서 지운다.
          accepted.current = null
          denied.current = true
          setState({ kind: 'unavailable' })
          return
        }
        const parsed = parseServiceNotices(payload)
        if (parsed === null) {
          failed()
          return
        }
        const next: Accepted = { notices: parsed, deadline: deadlineOf(parsed, Date.now()) }
        accepted.current = next
        setState({ kind: 'ready', ...next })
      } catch {
        // 네트워크 실패, JSON이 아닌 본문, 시간 제한.
        if (sequence.current === mine) failed()
      } finally {
        window.clearTimeout(deadline)
      }
    }
    void read()
    return () => {
      // 이 읽기를 무효로 한다. 답이 와도 누구의 것도 아니다.
      sequence.current += 1
      window.clearTimeout(deadline)
      controller.abort()
    }
  }, [ready, serial])

  /*
   * 결말이 커밋될 때 두 타이머가 함께 선다(layout effect, 저장소의 타이머 관용). 다시 읽기는 보이는 응답의 남은 신뢰 시간의
   * 절반에(실패하면 기한 전까지 유지된다), 확인하지 못한 상태는 일정 간격 뒤에 한다. 기한 타이머는 그 응답을 기한에
   * "확인하지 못했다"로 접는다. 거절된 세션은 묻지 않는다.
   */
  useLayoutEffect(() => {
    if (!ready || state.kind === 'checking' || denied.current) return
    const timers: number[] = []
    if (state.kind === 'ready') {
      const remaining = state.deadline - Date.now()
      timers.push(
        window.setTimeout(
          () => {
            setSerial((current) => current + 1)
          },
          Math.max(remaining / 2, NOTICE_REFRESH_FLOOR_MS),
        ),
        window.setTimeout(
          () => {
            if (accepted.current?.deadline === state.deadline) accepted.current = null
            setState({ kind: 'unavailable' })
          },
          Math.max(remaining, 0),
        ),
      )
    } else {
      timers.push(
        window.setTimeout(() => {
          setSerial((current) => current + 1)
        }, NOTICE_RETRY_MS),
      )
    }
    return () => {
      for (const timer of timers) window.clearTimeout(timer)
    }
  }, [ready, state])

  if (state.kind !== 'ready') return state
  // 렌더 시점에도 기한을 본다 — 기한이 지난 응답은 타이머를 기다리지 않고 보이지 않는다.
  // eslint-disable-next-line react-hooks/purity -- 기한 판정은 표시의 안전 경계다. 시계가 다르면 결과가 다른 것이 의도다(지난 공지를 한 번도 더 그리지 않는다).
  return Date.now() < state.deadline
    ? { kind: 'ready', notices: state.notices }
    : { kind: 'unavailable' }
}
