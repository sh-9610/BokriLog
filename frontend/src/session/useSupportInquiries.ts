import { useCallback, useEffect, useRef, useState } from 'react'

import { noticePath } from '../content/auth'
import {
  INQUIRY_CATEGORIES,
  INQUIRY_STATUSES,
  inquiryFieldMessage,
  inquiryRefusalMessage,
  sensitiveRuleMessage,
  SUPPORT_INQUIRIES_PATH,
  supportContent,
  type InquiryCategory,
  type InquiryField,
  type InquiryStatus,
  type InquiryTargetState,
  type SensitiveRule,
} from '../content/support'
import { objectOf, postJson, readJson, type JsonObject } from './http'
import { isInstant } from './instant'
import { replaceLocation } from './navigation'
import { SESSION_REQUEST_TIMEOUT_MS } from './useSessionState'

/** 내 문의 하나(Story 3.6). 본문·회신 이메일·사용자 식별자가 없다. */
export interface UserInquiry {
  readonly reference: string
  readonly category: InquiryCategory
  readonly title: string
  /** 내가 쓴 본문. 목록에서 펼쳐 본다. */
  readonly description: string
  readonly receivedAt: string
  /** 답변 목표 시각. 달력에 그 해가 없어 정하지 못했으면 `null`이다. */
  readonly targetAt: string | null
  readonly targetState: InquiryTargetState
  readonly status: InquiryStatus
  readonly answer: string | null
  readonly answeredAt: string | null
}

/** 접수 결과. */
export interface InquiryReceipt {
  readonly reference: string
  readonly receivedAt: string
  readonly targetAt: string | null
  readonly targetState: InquiryTargetState
}

/** 보낼 입력. 검증 전 원문이다. */
export interface InquiryDraft {
  readonly category: string
  readonly title: string
  readonly description: string
  readonly contact: string
  readonly replyEmail: string
}

/** 내 문의 목록의 상태. 확인 중에는 아무것도 주장하지 않는다. */
export type InquiryListView =
  | { readonly kind: 'checking' }
  | {
      readonly kind: 'loaded'
      readonly items: readonly UserInquiry[]
      readonly supportContact: string | null
      /** 다시 불러오기가 실패했다. 마지막으로 읽은 목록을 유지한다. */
      readonly refreshFailed: boolean
    }
  | { readonly kind: 'unavailable' }

/** 문의 조작의 결과 문장 재료. 화면의 단일 live region이 말한다. */
export type SupportNotice =
  | { readonly kind: 'sending' }
  | { readonly kind: 'received'; readonly receipt: InquiryReceipt }
  | { readonly kind: 'invalid' }
  | {
      readonly kind: 'refused'
      readonly code: string | null
      readonly field: InquiryField | null
      readonly rule: SensitiveRule | null
    }
  | { readonly kind: 'unanswered' }
  /** 응답을 받지 못한 접수가 있는데 입력이 바뀌었다. 새 키로 보내기 전에 목록을 다시 읽고 확인을 청한다. */
  | { readonly kind: 'checkFirst' }

/** 접수 한 번의 결말. 거절이면 서버가 짚은 칸과 규칙이다(화면이 그 칸에 안내를 붙인다). */
export type SubmitResult =
  | { readonly kind: 'received' }
  | {
      readonly kind: 'refused'
      readonly field: InquiryField | null
      readonly rule: SensitiveRule | null
    }
  | { readonly kind: 'unanswered' }
  /** 보내지 않았다 — 앞선 응답 없는 접수의 결과를 목록에서 먼저 확인하게 했다. 다시 누르면 새 문의로 보낸다. */
  | { readonly kind: 'held' }

const UNANSWERED: SubmitResult = { kind: 'unanswered' }

const REFUSED: SubmitResult = { kind: 'refused', field: null, rule: null }

export interface SupportInquiries {
  readonly list: InquiryListView
  readonly listLoading: boolean
  readonly reload: () => void
  readonly notice: SupportNotice | null
  /** 마지막으로 접수한 결과. 확인 블록이 보인다. */
  readonly receipt: InquiryReceipt | null
  readonly pending: boolean
  readonly submit: (draft: InquiryDraft) => Promise<SubmitResult>
  /** 보내기 전 검사가 막았다. 화면의 status가 그 사실을 한 번 말한다. */
  readonly reportInvalid: () => void
}

const CONTACT = /^[A-Za-z0-9._%+-]{1,64}@[A-Za-z0-9-]+(\.[A-Za-z0-9-]+)*\.[A-Za-z]{2,}$/

const REFERENCE = /^Q-[A-Z2-7]{10}$/

const FIELDS: readonly InquiryField[] = [
  'category',
  'title',
  'description',
  'contact',
  'replyEmail',
]

const RULES: readonly SensitiveRule[] = ['SECRET', 'WALLET', 'FINANCIAL', 'EMAIL']

function targetOf(
  body: JsonObject,
): { targetAt: string | null; targetState: InquiryTargetState } | null {
  if (body.targetState === 'SET' && isInstant(body.targetAt)) {
    return { targetAt: body.targetAt, targetState: 'SET' }
  }
  if (body.targetState === 'CALENDAR_PENDING' && body.targetAt == null) {
    return { targetAt: null, targetState: 'CALENDAR_PENDING' }
  }
  return null
}

function inquiryOf(value: unknown): UserInquiry | null {
  const body = objectOf(value)
  if (body === null) return null
  const category = INQUIRY_CATEGORIES.find((known) => known === body.category)
  const status = INQUIRY_STATUSES.find((known) => known === body.status)
  const target = targetOf(body)
  if (
    category === undefined ||
    status === undefined ||
    target === null ||
    typeof body.reference !== 'string' ||
    !REFERENCE.test(body.reference) ||
    typeof body.title !== 'string' ||
    typeof body.description !== 'string' ||
    !isInstant(body.receivedAt)
  ) {
    return null
  }
  const answered = status === 'ANSWERED' || status === 'RESOLVED'
  // 답변과 답변 시각은 답변 완료부터 있고 그 전에는 없다 — 서버 CHECK와 같은 짝이다.
  if (
    answered
      ? typeof body.answer !== 'string' || !isInstant(body.answeredAt)
      : body.answer != null || body.answeredAt != null
  ) {
    return null
  }
  return {
    reference: body.reference,
    category,
    title: body.title,
    description: body.description,
    receivedAt: body.receivedAt,
    ...target,
    status,
    answer: answered ? (body.answer as string) : null,
    answeredAt: answered ? (body.answeredAt as string) : null,
  }
}

/** 목록 응답. 하나라도 모르는 모양이면 묶음 전체를 믿지 않는다. */
export function parseInquiryList(
  payload: unknown,
): { items: readonly UserInquiry[]; supportContact: string | null } | null {
  const body = objectOf(payload)
  if (body === null || !Array.isArray(body.items)) return null
  const contact = body.supportContact
  // 연락처 하나가 모양이 어긋나도 목록을 버리지 않는다 — 그 필드만 없는 것으로 본다(허위 링크를 만들지 않는다).
  const supportContact = typeof contact === 'string' && CONTACT.test(contact) ? contact : null
  const items: UserInquiry[] = []
  for (const value of body.items as unknown[]) {
    const item = inquiryOf(value)
    if (item === null) return null
    items.push(item)
  }
  return { items, supportContact }
}

/**
 * 접수 응답. 같은 키의 재전송은 그 사이 운영자가 처리한 상태를 실어 올 수 있다 — 알려진 상태면 접수로 인정한다.
 */
export function parseReceipt(payload: unknown): InquiryReceipt | null {
  const body = objectOf(payload)
  if (body === null) return null
  const target = targetOf(body)
  if (
    target === null ||
    typeof body.reference !== 'string' ||
    !REFERENCE.test(body.reference) ||
    !isInstant(body.receivedAt) ||
    !INQUIRY_STATUSES.some((known) => known === body.status)
  ) {
    return null
  }
  return { reference: body.reference, receivedAt: body.receivedAt, ...target }
}

/**
 * 인증 사용자의 문의(Story 3.6 AC-4).
 *
 * 세션이 준비되면 내 문의 목록을 한 번 읽는다. 다시 읽기는 사용자의 조작(다시 불러오기)과 접수 성공 뒤뿐이다 — 자동
 * 재시도가 없다.
 *
 * <b>접수는 자동으로 다시 보내지 않는다.</b> 멱등 키는 입력 한 벌마다 한 번 만들고, 응답을 받지 못하거나(네트워크·시간
 * 제한) 5xx면 키를 그대로 두어 사용자가 같은 입력으로 다시 누를 때 <b>같은 키</b>로 보낸다 — 그 사이 서버가 이미 받았다면
 * 같은 접수 결과가 돌아온다. 입력은 화면이 들고 있고 실패에서 지우지 않는다. 401은 만료 이동 하나이고, 값은 메모리에만
 * 있다(브라우저 저장소에 쓰지 않는다).
 */
export function useSupportInquiries(ready: boolean, csrfToken: string | null): SupportInquiries {
  const [list, setList] = useState<InquiryListView>({ kind: 'checking' })
  const [listLoading, setListLoading] = useState(false)
  const [attempt, setAttempt] = useState(0)
  const [notice, setNotice] = useState<SupportNotice | null>(null)
  const [receipt, setReceipt] = useState<InquiryReceipt | null>(null)
  const [pending, setPending] = useState(false)
  const sequence = useRef(0)
  const inFlight = useRef(false)
  const key = useRef<{ readonly input: string; readonly value: string } | null>(null)
  /** 응답을 받지 못한 마지막 접수의 입력. 접수 여부를 모르는 채로 남아 있다. */
  const unanswered = useRef<string | null>(null)

  // 요청 시작은 passive effect다. 커밋이 보여 주는 것이 아니고 답은 어차피 다음 task에 온다.
  useEffect(() => {
    if (!ready) {
      return
    }
    sequence.current += 1
    const mine = sequence.current
    const controller = new AbortController()
    const deadline = window.setTimeout(() => {
      controller.abort()
    }, SESSION_REQUEST_TIMEOUT_MS)
    const failed = (): void => {
      setList((current) =>
        current.kind === 'loaded' ? { ...current, refreshFailed: true } : { kind: 'unavailable' },
      )
    }
    const read = async (): Promise<void> => {
      try {
        const response = await fetch(SUPPORT_INQUIRIES_PATH, {
          credentials: 'same-origin',
          cache: 'no-store',
          headers: { Accept: 'application/json' },
          signal: controller.signal,
        })
        const payload = await readJson(response)
        if (sequence.current !== mine) return
        if (response.status === 401) {
          // 세션이 끝났다. 만료의 결과는 이동 하나다.
          setList({ kind: 'unavailable' })
          replaceLocation(noticePath('expired'))
          return
        }
        if (response.status === 403 || response.status === 404) {
          // 거절됐다(삭제가 시작된 계정은 404). 목록을 메모리에서 지운다.
          setList({ kind: 'unavailable' })
          return
        }
        const parsed = response.status === 200 ? parseInquiryList(payload) : null
        if (parsed === null) {
          failed()
          return
        }
        setList({ kind: 'loaded', ...parsed, refreshFailed: false })
      } catch {
        if (sequence.current === mine) failed()
      } finally {
        window.clearTimeout(deadline)
        if (sequence.current === mine) setListLoading(false)
      }
    }
    void read()
    return () => {
      sequence.current += 1
      window.clearTimeout(deadline)
      controller.abort()
    }
  }, [ready, attempt])

  const reload = useCallback((): void => {
    setListLoading(true)
    setAttempt((value) => value + 1)
  }, [])

  const reportInvalid = useCallback((): void => {
    setNotice({ kind: 'invalid' })
  }, [])

  const submit = useCallback(
    async (draft: InquiryDraft): Promise<SubmitResult> => {
      if (inFlight.current) {
        return REFUSED
      }
      if (csrfToken === null) {
        setNotice({ kind: 'refused', code: 'FORBIDDEN', field: null, rule: null })
        return REFUSED
      }
      const body = {
        category: draft.category,
        title: draft.title.trim(),
        description: draft.description.trim(),
        contact: draft.contact,
        ...(draft.contact === 'EMAIL' ? { replyEmail: draft.replyEmail.trim() } : {}),
      }
      const text = JSON.stringify(body)
      if (unanswered.current !== null && unanswered.current !== text) {
        /*
         * 앞선 접수는 응답을 받지 못했는데 입력이 바뀌었다. 새 키로 보내면 같은 문의가 두 번 접수될 수 있다 — 먼저 목록을 다시
         * 읽어 이미 접수됐는지 보이고 확인을 청한다. 사용자가 다시 누르면 그때 새 문의로 보낸다.
         */
        unanswered.current = null
        key.current = null
        setNotice({ kind: 'checkFirst' })
        setListLoading(true)
        setAttempt((value) => value + 1)
        return { kind: 'held' }
      }
      // 같은 입력이면 같은 키다 — 응답을 받지 못한 요청을 사용자가 다시 보내도 두 번 접수되지 않는다.
      if (key.current?.input !== text) {
        key.current = { input: text, value: crypto.randomUUID() }
      }
      const idempotencyKey = key.current.value
      inFlight.current = true
      setPending(true)
      setNotice({ kind: 'sending' })
      const controller = new AbortController()
      const deadline = window.setTimeout(() => {
        controller.abort()
      }, SESSION_REQUEST_TIMEOUT_MS)
      try {
        // 본문은 키를 고른 `text`와 같은 객체다 — `postJson`의 `JSON.stringify`가 같은 문자열을 만든다.
        const response = await postJson(SUPPORT_INQUIRIES_PATH, {
          csrfToken,
          body,
          idempotencyKey,
          signal: controller.signal,
        })
        const payload = await readJson(response)
        if (response.status === 200 || response.status === 201) {
          const parsed = parseReceipt(payload)
          if (parsed === null) {
            // 서버는 받았을 수 있다. 키를 두고 단정하지 않는다.
            unanswered.current = text
            setNotice({ kind: 'unanswered' })
            return UNANSWERED
          }
          key.current = null
          unanswered.current = null
          setReceipt(parsed)
          setNotice({ kind: 'received', receipt: parsed })
          setListLoading(true)
          setAttempt((value) => value + 1)
          return { kind: 'received' }
        }
        if (response.status === 401) {
          replaceLocation(noticePath('expired'))
          setNotice({ kind: 'refused', code: 'UNAUTHENTICATED', field: null, rule: null })
          return REFUSED
        }
        if (response.status >= 500) {
          unanswered.current = text
          setNotice({ kind: 'unanswered' })
          return UNANSWERED
        }
        // 거절은 서버가 답했다 — 접수되지 않은 것이 확실하다.
        unanswered.current = null
        const fields = objectOf(payload)
        const code = fields !== null && typeof fields.code === 'string' ? fields.code : null
        const field = FIELDS.find((known) => known === fields?.field) ?? null
        const rule = RULES.find((known) => known === fields?.rule) ?? null
        if (code === 'IDEMPOTENCY_CONFLICT') {
          key.current = null
        }
        setNotice({ kind: 'refused', code, field, rule })
        return { kind: 'refused', field, rule }
      } catch {
        // 네트워크 실패·시간 제한. 접수 여부를 모른다 — 키와 입력을 그대로 둔다.
        unanswered.current = text
        setNotice({ kind: 'unanswered' })
        return UNANSWERED
      } finally {
        window.clearTimeout(deadline)
        inFlight.current = false
        setPending(false)
      }
    },
    [csrfToken],
  )

  return { list, listLoading, reload, notice, receipt, pending, submit, reportInvalid }
}

/**
 * 문의 조작의 결과 문장(화면의 단일 live region이 말한다). 거절은 서버가 짚은 칸·규칙의 문장이고, 그 밖은 code의
 * 문장이다. 말할 것이 없으면 `null`이다.
 */
export function supportSentence(notice: SupportNotice | null): string | null {
  if (notice === null) return null
  switch (notice.kind) {
    case 'sending':
      return supportContent.sending
    case 'received':
      return supportContent.received(notice.receipt.reference)
    case 'invalid':
      return supportContent.invalid
    case 'unanswered':
      return supportContent.unanswered
    case 'checkFirst':
      return supportContent.checkFirst
    case 'refused':
      if (notice.rule !== null) return sensitiveRuleMessage(notice.rule)
      if (notice.field !== null) return inquiryFieldMessage(notice.field)
      return inquiryRefusalMessage(notice.code)
  }
}
