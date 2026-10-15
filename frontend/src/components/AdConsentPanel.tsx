import { useLayoutEffect, useRef, type ReactElement } from 'react'

import type { AdConsentControl } from '../session/useAdConsent'

import {
  AD_CONSENT_POLICY_VERSION,
  adConsentContent,
  adConsentStateSentence,
  adConsentTimeText,
  type AdConsentChoice,
  type AdConsentState,
} from '../content/adConsent'
import styles from './AdConsentPanel.module.css'

const copy = adConsentContent

/** 상태마다 고를 수 있는 것. 미결정은 둘 다, 동의는 철회, 비동의는 다시 동의다. */
const ACTIONS: Readonly<
  Record<AdConsentState, readonly { readonly choice: AdConsentChoice; readonly label: string }[]>
> = {
  UNDECIDED: [
    { choice: 'GRANTED', label: copy.grant },
    { choice: 'DENIED', label: copy.deny },
  ],
  GRANTED: [{ choice: 'DENIED', label: copy.withdraw }],
  DENIED: [{ choice: 'GRANTED', label: copy.regrant }],
}

interface AdConsentPanelProps {
  readonly consent: AdConsentControl
  /** 화면의 다른 조작이 도는 중이다. 이 영역의 조작을 막는다. */
  readonly blocked: boolean
  /** 이 영역의 조작이 화면의 live region을 소유하게 한다. */
  readonly onAction: () => void
}

/**
 * 설정 화면의 `광고 동의` 영역(Story 4.1).
 *
 * 공급자·비개인화 문맥형 범위·광고 공급자에게 전달될 수 있는 것과 전달하지 않는 것을 먼저 설명하고, 지금 상태와 그 상태에서
 * 고를 수 있는 버튼을 둔다. 변경 중에는 <b>이 영역의 버튼만</b> `aria-disabled`로 잠근다. 저장 실패·네트워크 실패·시간 초과면
 * 이전 상태가 그대로 보이고, 결과는 화면의 live region이 한 번 말한다(이 영역은 live region을 더하지 않는다).
 *
 * 저장에 성공하면 누른 버튼이 다른 버튼으로 바뀌므로 초점을 지금 상태 문장으로 옮긴다 — 사라진 버튼과 함께 `<body>`로 떨어지지
 * 않게 한다. 거절·정책 변경으로 누른 버튼이 사라져도(상태를 지웠거나 바뀐 안내만 남았다) 초점을 이 영역의 문장으로 받는다. 그
 * 이동은 사용자가 누른 조작의 결과에만 있고, 응답이 오기 전에 사용자가 이 영역 밖의 다른 요소로 초점을 옮겼으면 뺏지 않는다.
 *
 * 이 영역은 광고를 띄우지 않고 외부 요청을 만들지 않는다. 동의 여부는 연결·동기화·대시보드에 쓰이지 않는다.
 */
export function AdConsentPanel({ blocked, consent, onAction }: AdConsentPanelProps): ReactElement {
  const sectionRef = useRef<HTMLElement>(null)
  const stateRef = useRef<HTMLParagraphElement>(null)
  const outdatedRef = useRef<HTMLParagraphElement>(null)
  const unavailableRef = useRef<HTMLParagraphElement>(null)
  /** 이 영역의 버튼이 시작한 변경의 결과를 아직 받지 않았다. 다른 경로의 갱신에는 초점을 옮기지 않는다. */
  const pressed = useRef(false)
  const locked = blocked || consent.pending

  const notice = consent.notice
  const view = consent.view
  const loading = consent.loading
  /*
   * 누른 조작의 결과로 초점을 옮긴다. 결과가 정해지는 커밋에서 돈다 — 정책 변경(409)은 상태를 다시 읽은 뒤에야 버튼이
   * 사라지므로 그 조회가 끝날 때까지(`loading`) 기다린다.
   */
  useLayoutEffect(() => {
    if (!pressed.current || notice === null || notice.kind === 'saving' || loading) {
      return
    }
    pressed.current = false
    const active = document.activeElement
    const lost = active === null || active === document.body || !active.isConnected
    // 응답을 기다리는 동안 사용자가 이 영역 밖의 다른 요소로 갔다. 늦은 결과가 초점을 뺏지 않는다.
    if (!lost && !(sectionRef.current?.contains(active) ?? false)) {
      return
    }
    if (notice.kind === 'saved' || notice.kind === 'changedElsewhere') {
      stateRef.current?.focus()
      return
    }
    // 누른 버튼이 사라졌다(상태를 지웠거나 바뀐 안내만 남았다). 남은 문장으로 받는다.
    if (lost) {
      ;(outdatedRef.current ?? stateRef.current ?? unavailableRef.current)?.focus()
    }
  }, [notice, view, loading])

  const choose = (choice: AdConsentChoice): void => {
    if (locked) return
    onAction()
    pressed.current = true
    void consent.decide(choice)
  }

  /** 다시 확인. 선택 버튼처럼 화면의 다른 흐름(`blocked`)과 이 조회 자신을 본다. */
  const reloadLocked = blocked || consent.loading
  const retry = (
    <button
      aria-disabled={reloadLocked}
      className={styles.secondary}
      onClick={() => {
        if (!reloadLocked) consent.reload()
      }}
      type="button"
    >
      {copy.retry}
    </button>
  )

  return (
    <section aria-labelledby={copy.id} className={styles.section} ref={sectionRef}>
      <h2 className={styles.heading} id={copy.id}>
        {copy.heading}
      </h2>
      <p>{copy.body}</p>

      <dl className={styles.facts}>
        <div>
          <dt>{copy.providerLabel}</dt>
          <dd>{copy.provider}</dd>
        </div>
        <div>
          <dt>{copy.scopeLabel}</dt>
          <dd>{copy.scope}</dd>
        </div>
      </dl>

      <div className={styles.flows}>
        <div>
          <h3 className={styles.subheading} id="ad-consent-shared-heading">
            {copy.sharedHeading}
          </h3>
          <ul aria-labelledby="ad-consent-shared-heading" className={styles.list}>
            {copy.shared.map((item) => (
              <li key={item}>{item}</li>
            ))}
          </ul>
        </div>
        <div>
          <h3 className={styles.subheading} id="ad-consent-not-shared-heading">
            {copy.notSharedHeading}
          </h3>
          <ul aria-labelledby="ad-consent-not-shared-heading" className={styles.list}>
            {copy.notShared.map((item) => (
              <li key={item}>{item}</li>
            ))}
          </ul>
        </div>
      </div>

      <p className={styles.independence}>{copy.independence}</p>
      <p className={styles.muted}>{copy.withdrawal}</p>

      {view.kind === 'checking' ? (
        <p className={styles.muted}>{copy.checking}</p>
      ) : view.kind === 'unavailable' ? (
        <>
          {/* 거절로 상태를 지웠을 때 초점이 오는 자리다. 정적 문장이고 live region이 아니다. */}
          <p className={styles.muted} ref={unavailableRef} tabIndex={-1}>
            {copy.unavailable}
          </p>
          {retry}
        </>
      ) : (
        <div className={styles.current}>
          <h3 className={styles.subheading} id="ad-consent-state-heading">
            {copy.stateLabel}
          </h3>
          {/* 저장 성공 뒤 초점이 오는 자리다. 정적 문장이고 live region이 아니다. */}
          <p className={styles.state} ref={stateRef} tabIndex={-1}>
            {adConsentStateSentence(view.consent.state)}
          </p>
          {view.consent.decidedAt === null ? null : (
            <p className={styles.muted}>
              {copy.decidedAtLabel}: {adConsentTimeText(view.consent.decidedAt)}
            </p>
          )}
          {view.refreshFailed ? (
            <>
              {/* 다시 읽기가 실패했다. 위 상태는 마지막으로 확인한 것이다. */}
              <p className={styles.muted}>{copy.refreshFailed}</p>
              {retry}
            </>
          ) : null}
          {view.consent.policyVersion === AD_CONSENT_POLICY_VERSION ? (
            <div className={styles.actions}>
              {ACTIONS[view.consent.state].map((action) => (
                /*
                  `aria-disabled`이지 `disabled`가 아니다 — 방금 `Enter`로 누른 키보드·스크린리더 사용자가 포커스를 잃지
                  않는다. 실제 중복 제출 차단은 훅의 가드가 한다. 동의와 비동의는 같은 모양이다 — 한쪽으로 기울이지
                  않는다.
                */
                <button
                  aria-disabled={locked}
                  className={styles.choice}
                  key={action.choice}
                  onClick={() => {
                    choose(action.choice)
                  }}
                  type="button"
                >
                  {action.label}
                </button>
              ))}
            </div>
          ) : (
            // 서버의 정책이 이 화면의 설명과 다르다. 바뀐 설명을 보기 전에는 고를 수 없다. 누른 버튼이 사라지면 초점이 온다.
            <p className={styles.independence} ref={outdatedRef} tabIndex={-1}>
              {copy.outdated}
            </p>
          )}
        </div>
      )}
    </section>
  )
}
