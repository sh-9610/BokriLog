import { useCallback, useEffect, useRef, useState } from 'react'

import {
  AD_CONSENT_PATH,
  AD_CONSENT_POLICY_VERSION,
  AD_CONSENT_STATES,
  adConsentContent,
  type AdConsentChoice,
  type AdConsentState,
} from '../content/adConsent'
import { CSRF_HEADER, noticePath } from '../content/auth'
import { isInstant } from './instant'
import { replaceLocation } from './navigation'
import { SESSION_REQUEST_TIMEOUT_MS } from './useSessionState'

/** 서버가 말한 지금 상태(Story 4.1). 사용자 식별자가 없다. */
export interface AdConsent {
  readonly state: AdConsentState
  /** 서버의 지금 정책 버전. 이 화면의 설명 버전과 다르면 고를 수 없다. */
  readonly policyVersion: string
  /** 지금 상태를 만든 결정의 시각. 미결정이면 `null`이다. */
  readonly decidedAt: string | null
}

/** 동의 상태 조회의 상태. 확인 중·확인 실패에는 아무것도 주장하지 않는다. */
export type AdConsentView =
  | { readonly kind: 'checking' }
  | {
      readonly kind: 'loaded'
      readonly consent: AdConsent
      /** 다시 읽기가 실패했다. 마지막으로 읽은 상태를 유지한다. */
      readonly refreshFailed: boolean
    }
  | { readonly kind: 'unavailable' }

/** 동의 조작의 결과 문장 재료. 화면의 단일 live region이 말한다. */
export type AdConsentNotice =
  | { readonly kind: 'saving' }
  | { readonly kind: 'saved'; readonly previous: AdConsentState; readonly next: AdConsentState }
  /** 저장하지 못했다(5xx·네트워크·시간 초과·모르는 응답). 이전 상태를 그대로 둔다. */
  | { readonly kind: 'failed' }
  /** 저장 응답의 지금 상태가 고른 것과 다르다(같은 키의 재전송 사이에 다른 곳에서 바꿨다). 성공이 아니다. */
  | { readonly kind: 'changedElsewhere' }
  /** 서버의 정책 버전이 이 화면의 설명과 다르다. 상태를 다시 읽었다. */
  | { readonly kind: 'policyChanged' }
  /** 서버가 거절했다(충돌·키·CSRF·계정 접근). */
  | { readonly kind: 'refused' }

export interface AdConsentControl {
  readonly view: AdConsentView
  readonly loading: boolean
  readonly reload: () => void
  readonly notice: AdConsentNotice | null
  readonly pending: boolean
  readonly decide: (choice: AdConsentChoice) => Promise<void>
}

type Body = Readonly<Record<string, unknown>>

function objectOf(value: unknown): Body | null {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
    ? (value as Body)
    : null
}

/**
 * 동의 상태 응답. 미결정이면 시각이 없고 그 밖에는 시각이 있다 — 서버가 지키는 짝이다. 모르는 모양은 믿지 않는다.
 */
export function parseAdConsent(payload: unknown): AdConsent | null {
  const body = objectOf(payload)
  if (body === null) return null
  const state = AD_CONSENT_STATES.find((known) => known === body.state)
  if (state === undefined || typeof body.policyVersion !== 'string' || body.policyVersion === '') {
    return null
  }
  if (state === 'UNDECIDED') {
    return body.decidedAt == null
      ? { state, policyVersion: body.policyVersion, decidedAt: null }
      : null
  }
  return isInstant(body.decidedAt)
    ? { state, policyVersion: body.policyVersion, decidedAt: body.decidedAt }
    : null
}

async function readJson(response: Response): Promise<unknown> {
  try {
    return await response.json()
  } catch {
    return null
  }
}

/**
 * 인증 사용자의 문맥형 광고 동의(Story 4.1).
 *
 * 세션이 준비되면 지금 상태를 한 번 읽는다. 다시 읽기는 사용자의 조작(다시 확인)과 정책 버전 불일치 응답 뒤뿐이다 — 자동
 * 재시도가 없다.
 *
 * <b>변경은 자동으로 다시 보내지 않는다.</b> 멱등 키는 선택 하나마다 한 번 만들고, 응답을 받지 못하거나(네트워크·시간 제한)
 * 5xx면 키를 그대로 두어 사용자가 같은 선택을 다시 누를 때 <b>같은 키</b>로 보낸다. 실패하면 이전 상태를 그대로 보이고 성공을
 * 말하지 않는다. 성공 응답이 오면 그 상태 하나로 교체하고, 그 사이 늦게 도착한 조회 응답은 버린다. 401은 만료 이동 하나이고,
 * 값은 메모리에만 있다(브라우저 저장소에 쓰지 않는다).
 */
export function useAdConsent(ready: boolean, csrfToken: string | null): AdConsentControl {
  const [view, setView] = useState<AdConsentView>({ kind: 'checking' })
  const [loading, setLoading] = useState(false)
  const [attempt, setAttempt] = useState(0)
  const [notice, setNotice] = useState<AdConsentNotice | null>(null)
  const [pending, setPending] = useState(false)
  /** 조회의 순번. 변경이 성공하면 올려 그보다 먼저 떠난 조회의 답을 버린다. */
  const sequence = useRef(0)
  const inFlight = useRef(false)
  const key = useRef<{ readonly choice: AdConsentChoice; readonly value: string } | null>(null)

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
      setView((current) =>
        current.kind === 'loaded' ? { ...current, refreshFailed: true } : { kind: 'unavailable' },
      )
    }
    const read = async (): Promise<void> => {
      try {
        const response = await fetch(AD_CONSENT_PATH, {
          credentials: 'same-origin',
          cache: 'no-store',
          headers: { Accept: 'application/json' },
          signal: controller.signal,
        })
        const payload = await readJson(response)
        if (sequence.current !== mine) return
        if (response.status === 401) {
          // 세션이 끝났다. 만료의 결과는 이동 하나다 — 화면을 따로 바꾸지 않는다.
          replaceLocation(noticePath('expired'))
          return
        }
        if (response.status === 403 || response.status === 404) {
          // 거절됐다(삭제가 시작된 계정은 404). 상태를 메모리에서 지운다.
          setView({ kind: 'unavailable' })
          return
        }
        const parsed = response.status === 200 ? parseAdConsent(payload) : null
        if (parsed === null) {
          failed()
          return
        }
        // 새로 읽은 상태가 기준이다. 실패 뒤 남아 있던 키가 낡은 결정을 다시 보내지 않게 비운다.
        key.current = null
        setView({ kind: 'loaded', consent: parsed, refreshFailed: false })
      } catch {
        if (sequence.current === mine) failed()
      } finally {
        window.clearTimeout(deadline)
        if (sequence.current === mine) setLoading(false)
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
    setLoading(true)
    setAttempt((value) => value + 1)
  }, [])

  const decide = useCallback(
    async (choice: AdConsentChoice): Promise<void> => {
      if (inFlight.current) {
        return
      }
      if (view.kind !== 'loaded') {
        return
      }
      if (csrfToken === null) {
        setNotice({ kind: 'refused' })
        return
      }
      const previous = view.consent.state
      // 같은 선택이면 같은 키다 — 응답을 받지 못한 요청을 사용자가 다시 눌러도 두 번 결정되지 않는다.
      if (key.current?.choice !== choice) {
        key.current = { choice, value: crypto.randomUUID() }
      }
      const idempotencyKey = key.current.value
      inFlight.current = true
      setPending(true)
      setNotice({ kind: 'saving' })
      const controller = new AbortController()
      const deadline = window.setTimeout(() => {
        controller.abort()
      }, SESSION_REQUEST_TIMEOUT_MS)
      try {
        const response = await fetch(AD_CONSENT_PATH, {
          method: 'POST',
          credentials: 'same-origin',
          cache: 'no-store',
          headers: {
            Accept: 'application/json',
            'Content-Type': 'application/json',
            [CSRF_HEADER]: csrfToken,
            'Idempotency-Key': idempotencyKey,
          },
          // 사용자가 읽은 설명의 정책 버전을 싣는다. 서버의 지금 버전과 다르면 저장되지 않는다.
          body: JSON.stringify({ choice, policyVersion: AD_CONSENT_POLICY_VERSION }),
          signal: controller.signal,
        })
        const payload = await readJson(response)
        if (response.status === 200) {
          const parsed = parseAdConsent(payload)
          if (parsed === null) {
            // 서버는 저장했을 수 있다. 키를 두고 단정하지 않는다 — 이전 상태를 보인다.
            setNotice({ kind: 'failed' })
            return
          }
          key.current = null
          // 이 응답이 지금 상태다. 먼저 떠난 조회의 늦은 답이 이것을 덮지 못하게 한다.
          sequence.current += 1
          setLoading(false)
          setView({ kind: 'loaded', consent: parsed, refreshFailed: false })
          // 같은 키의 재전송이 돌려준 지금 상태가 고른 것과 다르면(그 사이 다른 곳에서 바꿨다) 저장 성공으로 말하지 않는다.
          setNotice(
            parsed.state === choice
              ? { kind: 'saved', previous, next: parsed.state }
              : { kind: 'changedElsewhere' },
          )
          return
        }
        if (response.status === 401) {
          // 만료의 결과는 이동 하나다 — 문장도 상태도 바꾸지 않는다.
          replaceLocation(noticePath('expired'))
          return
        }
        if (response.status >= 500) {
          setNotice({ kind: 'failed' })
          return
        }
        // 거절은 서버가 답했다 — 저장되지 않은 것이 확실하다. 다음 누름은 새 키다.
        key.current = null
        const fields = objectOf(payload)
        const code = fields !== null && typeof fields.code === 'string' ? fields.code : null
        if (response.status === 403 || response.status === 404) {
          // 거절됐다(삭제가 시작된 계정은 404). 조회와 같이 상태를 메모리에서 지운다 — 이전 선택을 계속 주장하지 않는다.
          sequence.current += 1
          setLoading(false)
          setView({ kind: 'unavailable' })
          setNotice({ kind: 'refused' })
          return
        }
        if (code === 'AD_CONSENT_POLICY_CHANGED') {
          setNotice({ kind: 'policyChanged' })
          setLoading(true)
          setAttempt((value) => value + 1)
          return
        }
        setNotice({ kind: 'refused' })
      } catch {
        // 네트워크 실패·시간 제한. 저장 여부를 모른다 — 키를 그대로 두고 이전 상태를 보인다.
        setNotice({ kind: 'failed' })
      } finally {
        window.clearTimeout(deadline)
        inFlight.current = false
        setPending(false)
      }
    },
    [csrfToken, view],
  )

  return { view, loading, reload, notice, pending, decide }
}

/** 동의 조작의 결과 문장(화면의 단일 live region이 말한다). 말할 것이 없으면 `null`이다. */
export function adConsentSentence(notice: AdConsentNotice | null): string | null {
  if (notice === null) return null
  switch (notice.kind) {
    case 'saving':
      return adConsentContent.saving
    case 'saved':
      if (notice.next === 'GRANTED') return adConsentContent.savedGranted
      if (notice.previous === 'GRANTED') return adConsentContent.withdrawn
      return adConsentContent.savedDenied
    case 'changedElsewhere':
      return adConsentContent.changedElsewhere
    case 'failed':
      return adConsentContent.failed
    case 'policyChanged':
      return adConsentContent.outdated
    case 'refused':
      return adConsentContent.refused
  }
}
