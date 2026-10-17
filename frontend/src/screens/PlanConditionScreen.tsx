import { useState, type ReactElement } from 'react'

import { PlanConditionPanel } from '../components/PlanConditionPanel'
import { ReauthPanel } from '../components/ReauthPanel'
import { SignOutButton } from '../components/SignOutButton'
import { authContent, DASHBOARD_PATH, reauthNoticeKindOf, SETTINGS_PATH } from '../content/auth'
import { entryContent } from '../content/entry'
import { planConditionContent } from '../content/planConditions'
import { useReauthentication } from '../session/useReauthentication'
import { liveRegionMessage, useSessionGate } from '../session/useSessionState'
import { useSignOut } from '../session/useSignOut'
import { useTelemetry } from '../telemetry/useTelemetry'
import { EntryScreen } from './EntryScreen'
import styles from './SignedInScreen.module.css'

const MAIN_ID = 'plan-condition-main'

/** 로그인 세션 안에서 첫 공식 스냅샷을 기준으로 계획 조건을 검토하는 화면. */
export function PlanConditionScreen(): ReactElement {
  const [preparationMessage, setPreparationMessage] = useState('')
  const { csrfToken, gate } = useSessionGate('settings')
  const signOut = useSignOut(csrfToken)
  const reauthentication = useReauthentication(csrfToken)
  // 이 문서의 Web Vitals(Story 4.5). 세션 토큰이 있을 때만 숨김·떠남에서 보낸다.
  useTelemetry(planConditionContent.path, csrfToken)

  if (gate === 'unauthenticated') return <EntryScreen notice="signedOut" />
  const ready = gate === 'ready'
  const reauthRequired = gate === 'reauth-required'

  return (
    <div className={styles.page}>
      <a className={styles.skipLink} href={`#${MAIN_ID}`}>
        {entryContent.skipLink}
      </a>
      <header className={styles.header}>
        <p className={styles.brand}>{entryContent.brand}</p>
        {ready ? (
          <div className={styles.headerActions}>
            <a className={styles.settingsLink} href={DASHBOARD_PATH}>
              대시보드
            </a>
            <a className={styles.settingsLink} href={SETTINGS_PATH}>
              {authContent.settings.link}
            </a>
            <SignOutButton onSignOut={signOut.requestSignOut} pending={signOut.pending} />
          </div>
        ) : null}
      </header>
      <main className={styles.main} id={MAIN_ID} tabIndex={-1}>
        <div className={styles.intro}>
          <h1 className={styles.title}>{authContent.pageTitle}</h1>
          <p className={styles.disclaimer}>{authContent.disclaimer}</p>
        </div>
        <div
          aria-busy={(!ready && !reauthRequired) || signOut.pending || reauthentication.pending}
          aria-live="polite"
          className={styles.notice}
          role="status"
        >
          {ready && !signOut.pending ? (
            <p>{preparationMessage}</p>
          ) : (
            <p className={styles.noticeText}>
              {liveRegionMessage(gate, signOut.pending, reauthentication.pending)}
            </p>
          )}
        </div>
        {reauthRequired ? (
          <ReauthPanel
            notice={reauthNoticeKindOf(window.location.search)}
            onReauthenticate={reauthentication.requestReauthentication}
            onSignOut={signOut.requestSignOut}
            pending={reauthentication.pending}
            signingOut={signOut.pending}
          />
        ) : null}
        {ready ? (
          <PlanConditionPanel
            csrfToken={csrfToken}
            ready={ready}
            announce={setPreparationMessage}
          />
        ) : null}
      </main>
    </div>
  )
}
