import { useLayoutEffect, useRef, type ReactElement } from 'react'

import type { Accepted } from '../session/useDashboard'
import type { DashboardSync } from '../session/useDashboardSync'
import type { PlanEnd } from '../session/usePlanEnd'
import type { ReauthenticationAccess } from '../session/useReauthentication'

import { authContent, SIGN_IN_PROVIDERS, type SignInProvider } from '../content/auth'
import { planEndContent as copy } from '../content/planEnd'
import { copyFor } from '../content/planResult'
import { jobInProgress, noticeCopy } from '../session/usePlanEnd'
import styles from './PlanEndControl.module.css'
import { ProviderMark } from './ProviderMark'
// The provider buttons are the reauthentication panel's own: one set of brand rules, not another copy.
import reauthStyles from './ReauthPanel.module.css'

const BUSY_ID = 'plan-end-busy'
const PREPARING_ID = 'plan-end-preparing'
const DIALOG_ID = 'plan-end-dialog-open'
const LEAVING_ID = 'plan-end-leaving'

/**
 * Provider button rules: a closed map, as in the reauthentication panel. The value type carries `undefined` because
 * of the CSS module's type (`noUncheckedIndexedAccess`).
 */
const PROVIDER_BUTTON_CLASS: Readonly<Record<SignInProvider, string | undefined>> = {
  google: reauthStyles.googleButton,
  naver: reauthStyles.naverButton,
  kakao: reauthStyles.kakaoButton,
}

/**
 * The `계획 종료` area of the active plan's panel (Story 2.13), after the plan terms.
 *
 * What it offers follows the server's judgement in the same read (`planEnd`): `AVAILABLE` — what ending does and
 * keeps, and one secondary `계획 중도 종료` button at least 44px; `NEW_PLAN_CHOICE` (a long gap) — only a note that the
 * long-gap choice `새 계획 시작` ends it, named and never placed; `UNAVAILABLE` — only the reason. The button is
 * `aria-disabled` (it keeps its place and focus; a press does nothing) while the prepare runs, while the dialog is
 * open, while a reauthentication is leaving for its provider, and while a job is in progress (the catch-up's gate),
 * each cause with its own line describing it.
 *
 * When the server asks for a recent reauthentication, the guidance and the three provider buttons appear here (the
 * existing reauthentication start); the user comes back and presses again — nothing continues by itself. A refused
 * request is said here, and so is an end whose record did not come on screen, or a dialog closed while whether the
 * plan ended was not known. A press that ends either way moves focus to that block (`tabIndex=-1`); either end
 * button (this one, or `새 계획 시작` in the long-gap choice) can be the one pressed.
 *
 * No live region of its own: the screen's single status region says what the flow did, once.
 */
export function PlanEndControl({
  bundle,
  planEnd,
  reauthentication,
  sync,
}: {
  readonly bundle: Accepted
  readonly planEnd: PlanEnd
  readonly reauthentication: ReauthenticationAccess
  readonly sync: DashboardSync
}): ReactElement {
  const noticeRef = useRef<HTMLDivElement>(null)
  const buttonRef = useRef<HTMLButtonElement>(null)
  // A focus request made before this area was mounted is not replayed.
  const handled = useRef(planEnd.focus?.serial ?? 0)
  const { focus, notice } = planEnd
  const { availability, reason } = bundle.planEnd

  // A layout effect, like the panel's focus moves: focus reaches the notice in the commit that shows it.
  useLayoutEffect(() => {
    if (focus === null || focus.serial === handled.current || focus.target !== 'notice') return
    handled.current = focus.serial
    ;(noticeRef.current ?? buttonRef.current)?.focus()
  }, [focus])

  const busy = jobInProgress(sync, bundle)
  const dialogOpen = planEnd.dialog !== null
  const leaving = reauthentication.pending
  const blocked = planEnd.busy || leaving || busy
  // Every cause that blocks the button is said, and describes it.
  const described = [
    planEnd.preparing ? PREPARING_ID : null,
    dialogOpen ? DIALOG_ID : null,
    leaving ? LEAVING_ID : null,
    busy ? BUSY_ID : null,
  ].filter((id) => id !== null)
  const said = noticeCopy(notice)

  return (
    <section aria-labelledby={copy.area.headingId} className={styles.area} id={copy.area.id}>
      <h3 id={copy.area.headingId}>{copy.area.heading}</h3>
      {availability === 'AVAILABLE' ? (
        <>
          <p>{copy.area.text}</p>
          <button
            aria-describedby={described.length === 0 ? undefined : described.join(' ')}
            aria-disabled={blocked}
            className={styles.button}
            id={copy.area.actionId}
            onClick={() => {
              if (!blocked) planEnd.request('USER_REQUEST')
            }}
            ref={buttonRef}
            type="button"
          >
            {copy.area.action}
          </button>
          {dialogOpen ? (
            <p className={styles.help} id={DIALOG_ID}>
              {copy.area.dialogOpen}
            </p>
          ) : null}
          {leaving ? (
            <p className={styles.help} id={LEAVING_ID}>
              {copy.area.leaving}
            </p>
          ) : null}
          {busy ? (
            <p className={styles.help} id={BUSY_ID}>
              {copy.area.busy}
            </p>
          ) : null}
        </>
      ) : availability === 'NEW_PLAN_CHOICE' ? (
        <p>{copy.area.newPlanChoice}</p>
      ) : (
        <p className={styles.state}>
          {copy.area.unavailableLabel}:{' '}
          {copyFor(copy.area.reasons, reason ?? '', copy.area.unknownReason)}
        </p>
      )}
      {planEnd.preparing ? (
        <p className={styles.state} id={PREPARING_ID}>
          <span aria-hidden="true" className={styles.marker} />
          <span>{copy.area.preparing}</span>
        </p>
      ) : null}
      {notice?.kind === 'reauthRequired' ? (
        <div className={styles.notice} ref={noticeRef} tabIndex={-1}>
          <p className={styles.label}>{copy.reauth.label}</p>
          <p>{copy.reauth.text}</p>
          <div className={reauthStyles.providerList}>
            {SIGN_IN_PROVIDERS.map((provider) => (
              <button
                /*
                 * `aria-disabled`, not `disabled`: the user who just pressed it keeps focus. The real guard is the
                 * reauthentication hook's (`useReauthentication`).
                 */
                aria-disabled={reauthentication.pending || planEnd.busy}
                className={PROVIDER_BUTTON_CLASS[provider]}
                key={provider}
                onClick={() => {
                  if (reauthentication.pending || planEnd.busy) return
                  reauthentication.requestReauthentication(provider)
                }}
                type="button"
              >
                <ProviderMark provider={provider} />
                <span>{authContent.settings.reauth.labels[provider]}</span>
              </button>
            ))}
          </div>
          <p className={styles.help}>{authContent.reauth.boundary}</p>
        </div>
      ) : null}
      {said === null ? null : (
        <div className={styles.notice} ref={noticeRef} tabIndex={-1}>
          <p className={styles.label}>{said.label}</p>
          <p>{said.text}</p>
        </div>
      )}
    </section>
  )
}
